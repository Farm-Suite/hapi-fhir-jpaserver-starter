package org.farmsuite.fhir.usecase;

import org.farmsuite.fhir.config.AdministrationClientConfig.AdministrationClientProperties;
import org.farmsuite.sso.client.OAuth2ClientCredentialsClient;
import org.hl7.fhir.r5.model.Task;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit test puro del notificador del espejo de Task.owner: qué owners se espejan (Practitioner y
 * Organization), qué cuerpo se manda (incluido el alias legado) y que un fallo de
 * Administration-MIC nunca se propaga a la escritura FHIR.
 */
class TaskOwnerSyncNotifyTest {

    private static final String BASE = "http://admin.test/administration/api";

    private MockRestServiceServer server;
    private TaskOwnerSyncNotify notify;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OAuth2ClientCredentialsClient tokenClient = mock(OAuth2ClientCredentialsClient.class, RETURNS_DEEP_STUBS);
        when(tokenClient.getToken().authorizationHeaderValue()).thenReturn("Bearer t");
        AdministrationClientProperties properties = new AdministrationClientProperties();
        properties.setBaseUrl(BASE);
        notify = new TaskOwnerSyncNotify(builder.build(), tokenClient, properties);
    }

    @Test
    void sync_practitionerOnly_sendsThePractitionerAndTheLegacyAlias() {
        server.expect(requestTo(BASE + "/practitioner-tasks/t1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer t"))
                .andExpect(jsonPath("$.assignedPractitionerId").value("pr-1"))
                .andExpect(jsonPath("$.assignedOrganizationId").doesNotExist())
                .andExpect(jsonPath("$.practitionerId").value("pr-1"))
                .andExpect(jsonPath("$.tenantId").value("farm-a"))
                .andExpect(jsonPath("$.organizationId").value("org-loc"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        notify.sync(task("t1"), "farm-a", "org-loc", new TaskAssignment("pr-1", null, null));

        server.verify();
    }

    @Test
    void sync_organizationOnly_isAQueueWithoutPractitionerAlias() {
        server.expect(requestTo(BASE + "/practitioner-tasks/t1"))
                .andExpect(jsonPath("$.assignedOrganizationId").value("org-9"))
                .andExpect(jsonPath("$.assignedPractitionerId").doesNotExist())
                .andExpect(jsonPath("$.practitionerId").doesNotExist())
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        notify.sync(task("t1"), "farm-a", null, new TaskAssignment(null, "org-9", null));

        server.verify();
    }

    @Test
    void sync_bothPractitionerAndOrganization_andTheCreatorOrganization() {
        server.expect(requestTo(BASE + "/practitioner-tasks/t1"))
                .andExpect(jsonPath("$.assignedPractitionerId").value("pr-1"))
                .andExpect(jsonPath("$.assignedOrganizationId").value("org-9"))
                .andExpect(jsonPath("$.createdByOrganizationId").value("org-1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        notify.sync(task("t1"), "farm-a", null, new TaskAssignment("pr-1", "org-9", "org-1"));

        server.verify();
    }

    @Test
    void sync_withoutAssignmentOrTenant_isNotMirrored() {
        notify.sync(task("t1"), "farm-a", null, null);
        notify.sync(task("t1"), null, null, new TaskAssignment("pr-1", null, null));

        server.verify(); // sin expectativas: cualquier llamada habría fallado el mock
    }

    @Test
    void sync_swallowsAdministrationFailure() {
        server.expect(requestTo(BASE + "/practitioner-tasks/t1")).andRespond(withServerError());

        notify.sync(task("t1"), "farm-a", null, new TaskAssignment(null, "org-9", null)); // no debe lanzar

        server.verify();
    }

    @Test
    void sync_doesNotBlockTheTaskWriteWhenTheTokenEndpointHangs() {
        OAuth2ClientCredentialsClient hanging = mock(OAuth2ClientCredentialsClient.class);
        when(hanging.getToken()).thenAnswer(invocation -> {
            Thread.sleep(30_000);
            return null;
        });
        AdministrationClientProperties properties = new AdministrationClientProperties();
        properties.setBaseUrl(BASE);
        properties.setTimeout(Duration.ofMillis(300));
        TaskOwnerSyncNotify slow = new TaskOwnerSyncNotify(RestClient.builder().build(), hanging, properties);

        long start = System.nanoTime();
        slow.sync(task("t1"), "farm-a", null, new TaskAssignment(null, "org-9", null)); // no debe lanzar ni esperar 30 s
        slow.remove("t1");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    private Task task(String id) {
        Task task = new Task();
        task.setId(id);
        task.setStatus(Task.TaskStatus.REQUESTED);
        return task;
    }
}
