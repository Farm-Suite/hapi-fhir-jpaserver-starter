package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.usecase.TaskOwnerSyncNotify;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Location;
import org.hl7.fhir.r5.model.Task;
import org.springframework.stereotype.Component;

/**
 * Mantiene el espejo de Administration-MIC sincronizado durante todo el
 * ciclo de vida de una Task, no solo en la asignación inicial:
 *
 * <ul>
 *   <li>Creada/actualizada CON `owner` → upsert (también recoge cambios de
 *       `status` posteriores, no solo la primera asignación).</li>
 *   <li>Actualizada SIN `owner` (se quitó la asignación) → borra el espejo.</li>
 *   <li>Borrada en FHIR → borra el espejo, tenga o no owner.</li>
 * </ul>
 *
 * No distingue si el owner cambió específicamente en este update o ya venía
 * de antes: resincronizar/reborrar de más es inofensivo, tanto el upsert
 * como el borrado son idempotentes del lado de Administration-MIC.
 */
@Slf4j
@Interceptor
@Component
@RequiredArgsConstructor
public class TaskOwnerSyncInterceptor {

    private final TaskOwnerSyncNotify taskOwnerSyncNotify;
    private final DaoRegistry daoRegistry;

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onCreated(
            IBaseResource theResource,
            RequestDetails theRequestDetails,
            RequestPartitionId theRequestPartitionId
    ) {
        // Al crear, sin owner no hay nada que espejar todavía — a diferencia
        // de onUpdated, no hace falta un remove() de más (nunca tuvo espejo).
        if (!(theResource instanceof Task task) || !hasOwnerReference(task)) return;
        String tenantId = resolveTenantId(theRequestDetails, theRequestPartitionId);
        taskOwnerSyncNotify.sync(task, tenantId, resolveOrganizationId(task, tenantId));
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void onUpdated(
            IBaseResource theOldResource,
            IBaseResource theNewResource,
            RequestDetails theRequestDetails,
            RequestPartitionId theRequestPartitionId
    ) {
        // Aquí sí puede haber un espejo previo que ya no corresponda (owner
        // quitado), así que la rama sin owner llama remove(), no un no-op.
        if (!(theNewResource instanceof Task task)) return;
        if (hasOwnerReference(task)) {
            String tenantId = resolveTenantId(theRequestDetails, theRequestPartitionId);
            taskOwnerSyncNotify.sync(task, tenantId, resolveOrganizationId(task, tenantId));
        } else {
            taskOwnerSyncNotify.remove(task.getIdElement().getIdPart());
        }
    }

    /**
     * {@code theRequestPartitionId} puede venir {@code null} en estos pointcuts (confirmado en
     * runtime — HAPI no lo garantiza poblado en `STORAGE_PRECOMMIT_RESOURCE_*`, a diferencia de
     * `STORAGE_PARTITION_IDENTIFY_*`). `RequestDetails.getTenantId()` es la misma fuente que ya usa
     * `RequestTenantInterceptor` para resolver la partición desde la URL, así que es un fallback
     * fiel, no una aproximación.
     */
    private String resolveTenantId(RequestDetails theRequestDetails, RequestPartitionId theRequestPartitionId) {
        if (theRequestPartitionId != null) {
            String fromPartition = theRequestPartitionId.getFirstPartitionNameOrNull();
            if (fromPartition != null) return fromPartition;
        }
        return theRequestDetails == null ? null : theRequestDetails.getTenantId();
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
    public void onDeleted(
            IBaseResource theResource,
            RequestDetails theRequestDetails,
            RequestPartitionId theRequestPartitionId
    ) {
        if (!(theResource instanceof Task task)) return;
        taskOwnerSyncNotify.remove(task.getIdElement().getIdPart());
    }

    private boolean hasOwnerReference(Task task) {
        return task.hasOwner() && task.getOwner().hasReference();
    }

    /**
     * Task no tiene un elemento nativo de Organization — se resuelve vía
     * Task.location -> Location.managingOrganization (toda Location cuelga de
     * una Organization, fijado una sola vez al crearla). Best-effort: si la
     * Task no tiene location, o la Location no se puede leer, no bloquea el
     * espejo — solo queda sin organizationId (igual que las tareas de hoy).
     */
    private String resolveOrganizationId(Task task, String tenantId) {
        if (!task.hasLocation() || !task.getLocation().hasReference() || tenantId == null) return null;
        try {
            IFhirResourceDao<Location> locationDao = daoRegistry.getResourceDao(Location.class);
            SystemRequestDetails requestDetails = new SystemRequestDetails().setRequestPartitionId(RequestPartitionId.fromPartitionName(tenantId));
            Location location = locationDao.read(new IdType(task.getLocation().getReference()), requestDetails);
            if (location == null || !location.hasManagingOrganization() || !location.getManagingOrganization().hasReference()) {
                return null;
            }
            return location.getManagingOrganization().getReferenceElement().getIdPart();
        } catch (Exception e) {
            log.warn("No se pudo resolver la Organization de la Location {} de la Task {}",
                    task.getLocation().getReference(), task.getIdElement().getIdPart(), e);
            return null;
        }
    }
}
