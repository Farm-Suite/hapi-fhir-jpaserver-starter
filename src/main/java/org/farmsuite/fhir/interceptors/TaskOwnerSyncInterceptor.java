package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import lombok.RequiredArgsConstructor;
import org.farmsuite.fhir.usecase.TaskOwnerSyncNotify;
import org.hl7.fhir.instance.model.api.IBaseResource;
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
@Interceptor
@Component
@RequiredArgsConstructor
public class TaskOwnerSyncInterceptor {

    private final TaskOwnerSyncNotify taskOwnerSyncNotify;

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onCreated(
            IBaseResource theResource,
            RequestDetails theRequestDetails,
            RequestPartitionId theRequestPartitionId
    ) {
        // Al crear, sin owner no hay nada que espejar todavía — a diferencia
        // de onUpdated, no hace falta un remove() de más (nunca tuvo espejo).
        if (!(theResource instanceof Task task) || !hasOwnerReference(task)) return;
        taskOwnerSyncNotify.sync(task, theRequestPartitionId.getFirstPartitionNameOrNull());
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
            taskOwnerSyncNotify.sync(task, theRequestPartitionId.getFirstPartitionNameOrNull());
        } else {
            taskOwnerSyncNotify.remove(task.getIdElement().getIdPart());
        }
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
}
