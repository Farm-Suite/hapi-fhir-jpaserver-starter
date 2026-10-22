package org.farmsuite.fhir.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.config.AdministrationClientConfig.AdministrationClientProperties;
import org.farmsuite.sso.client.OAuth2ClientCredentialsClient;
import org.hl7.fhir.r5.model.Task;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sincroniza el espejo de una Task en Administration-MIC cuando tiene un
 * `owner` (Practitioner, Organization o PractitionerRole) asignado — el "índice espejo" para que un practitioner liste su
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

    /** Hilos daemon: no deben impedir el apagado del servidor si una llamada queda colgada. */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "task-owner-sync");
        thread.setDaemon(true);
        return thread;
    });

    private final RestClient restClient;
    private final OAuth2ClientCredentialsClient tokenClient;
    private final AdministrationClientProperties properties;

    public void sync(Task task, String tenantId, String locationOrganizationId, TaskAssignment assignment) {
        if (tenantId == null || assignment == null) return;

        String taskId = task.getIdElement().getIdPart();

        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId);
        body.put("assignedPractitionerId", assignment.practitionerId());
        body.put("assignedOrganizationId", assignment.organizationId());
        body.put("createdByOrganizationId", assignment.createdByOrganizationId());
        // Alias legado: una Administration-MIC aún sin desplegar solo entiende practitionerId.
        if (assignment.practitionerId() != null) body.put("practitionerId", assignment.practitionerId());
        body.put("organizationId", locationOrganizationId);
        body.put("status", task.getStatus().toCode());
        body.put("date", toOffsetDateTime(task.getAuthoredOn()));

        try {
            runBounded(() -> restClient.put()
                    .uri(properties.getBaseUrl() + "/practitioner-tasks/{taskId}", taskId)
                    .header("Authorization", tokenClient.getToken().authorizationHeaderValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity());
            log.debug("Task {} sincronizada con Administration-MIC (practitioner={}, organization={}, tenant={})", taskId, assignment.practitionerId(), assignment.organizationId(), tenantId);
        } catch (Exception e) {
            log.warn("No se pudo sincronizar Task {} (practitioner={}, organization={}, tenant={}) con Administration-MIC", taskId, assignment.practitionerId(), assignment.organizationId(), tenantId, e);
        }
    }

    /**
     * Se llama desde un hook PRECOMMIT: la transacción de la Task (y su conexión) sigue abierta
     * mientras dura esto. Ni el cliente OAuth ni el RestClient tienen timeout propio, así que un
     * Keycloak o un Administration-MIC lentos bloquearían la escritura de cualquier Task. Se acota
     * el tiempo total (token + llamada) con {@code farmsuite.administration.timeout}; al vencer se
     * interrumpe la llamada y el espejo queda desfasado (best-effort, ver la nota de la clase).
     */
    private void runBounded(Runnable call) throws Exception {
        Future<?> future = EXECUTOR.submit(call);
        try {
            future.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Administration-MIC no respondió en " + properties.getTimeout(), e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
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
            runBounded(() -> restClient.delete()
                    .uri(properties.getBaseUrl() + "/practitioner-tasks/{taskId}", taskId)
                    .header("Authorization", tokenClient.getToken().authorizationHeaderValue())
                    .retrieve()
                    .toBodilessEntity());
            log.debug("Espejo de Task {} borrado en Administration-MIC", taskId);
        } catch (Exception e) {
            log.warn("No se pudo borrar el espejo de Task {} en Administration-MIC", taskId, e);
        }
    }
}
