package org.farmsuite.fhir.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.config.AdministrationClientConfig.AdministrationClientProperties;
import org.farmsuite.sso.client.OAuth2ClientCredentialsClient;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;

/**
 * Sincroniza el espejo de una Task en Administration-MIC cuando tiene un
 * `owner` asignado — el "índice espejo" para que un practitioner liste su
 * actividad cruzando tenants sin depender de búsqueda por referencia entre
 * particiones distintas de HAPI (Task.owner -> Practitioner, que vive en
 * DEFAULT). Solo lo mínimo: tenant, practitioner, status, fecha.
 *
 * <p>Best effort a propósito: Administration-MIC caído o lento no debe tumbar
 * la escritura real de la Task en FHIR — cualquier fallo se registra y se
 * traga, nunca se propaga fuera de {@link #sync}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskOwnerSyncNotify {

    private static final String PRACTITIONER_PREFIX = "Practitioner/";

    private final RestClient restClient;
    private final OAuth2ClientCredentialsClient tokenClient;
    private final AdministrationClientProperties properties;

    public void sync(Task task, String tenantId) {
        if (tenantId == null) return;

        Reference owner = task.getOwner();
        if (owner == null || !owner.hasReference() || !owner.getReference().startsWith(PRACTITIONER_PREFIX)) return;
        String practitionerId = owner.getReference().substring(PRACTITIONER_PREFIX.length());
        String taskId = task.getIdElement().getIdPart();

        try {
            restClient.put()
                    .uri(properties.getBaseUrl() + "/practitioner-tasks/{taskId}", taskId)
                    .header("Authorization", tokenClient.getToken().authorizationHeaderValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "tenantId", tenantId,
                            "practitionerId", practitionerId,
                            "status", task.getStatus().toCode(),
                            "date", toOffsetDateTime(task.getAuthoredOn())
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Task {} sincronizada con Administration-MIC (practitioner={}, tenant={})", taskId, practitionerId, tenantId);
        } catch (Exception e) {
            log.warn("No se pudo sincronizar Task {} (owner={}, tenant={}) con Administration-MIC", taskId, practitionerId, tenantId, e);
        }
    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        return date == null ? OffsetDateTime.now(ZoneOffset.UTC) : date.toInstant().atOffset(ZoneOffset.UTC);
    }

    /**
     * Borra el espejo — se llama tanto cuando a una Task se le quita el
     * `owner` (ya no debe aparecer en la actividad de nadie) como cuando la
     * Task se borra del todo en FHIR. Idempotente del lado de
     * Administration-MIC: borrar un taskId sin espejo no falla.
     */
    public void remove(String taskId) {
        try {
            restClient.delete()
                    .uri(properties.getBaseUrl() + "/practitioner-tasks/{taskId}", taskId)
                    .header("Authorization", tokenClient.getToken().authorizationHeaderValue())
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Espejo de Task {} borrado en Administration-MIC", taskId);
        } catch (Exception e) {
            log.warn("No se pudo borrar el espejo de Task {} en Administration-MIC", taskId, e);
        }
    }
}
