package org.farmsuite.fhir;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Trazabilidad contra un servidor HAPI real (ver {@link AbstractFarmSuiteServerIT}).
 *
 * <p>Lo que se comprueba es lo que los tests unitarios no pueden: que los hooks se ejecutan, que
 * escribir Provenance/AuditEvent desde ellos funciona dentro de la transacción de HAPI y que un
 * usuario SIN permiso sobre Provenance/AuditEvent no impide que se escriban (los crea el servidor).
 */
class TaskTraceabilityIT extends AbstractFarmSuiteServerIT {

	@Test
	void taskLifecycle_leavesProvenanceWithActivityAndActor_evenIfUserCannotWriteProvenance() {
		// Un usuario humano con permisos SOLO sobre Task: nunca podría escribir Provenance por la API.
		asHuman("pr-7", List.of("Task.rscud", "Provenance.rs"));

		Task task = new Task();
		task.setStatus(Task.TaskStatus.REQUESTED);
		task.setIntent(Task.TaskIntent.ORDER);
		task.setOwner(new Reference("Organization/org-1"));
		task.setRequester(new Reference("Organization/org-1"));
		String id = tenantClient.create().resource(task).execute().getId().getIdPart();

		// Delegar: la organización pasa la tarea a una persona y la acepta.
		Task stored = tenantClient.read().resource(Task.class).withId(id).execute();
		stored.setOwner(new Reference("PractitionerRole/role-9"));
		stored.setStatus(Task.TaskStatus.ACCEPTED);
		tenantClient.update().resource(stored).execute();

		Bundle bundle = tenantClient.search().forResource(Provenance.class)
			.where(Provenance.TARGET.hasId("Task/" + id)).returnBundle(Bundle.class).execute();
		List<Provenance> provenances = bundle.getEntry().stream().map(e -> (Provenance) e.getResource()).toList();

		assertThat(provenances).extracting(p -> p.getActivity().getCodingFirstRep().getCode())
			.containsExactlyInAnyOrder("create", "assign", "delegate", "status-change");
		assertThat(provenances).allSatisfy(p ->
			assertThat(p.getAgentFirstRep().getWho().getReference()).isEqualTo("Practitioner/pr-7"));

		// El espejo vio primero la cola de la organización (sin profesional) y luego, al delegar en un
		// PractitionerRole, el profesional y su organización juntos; la creadora viaja en ambas.
		assertThat(MIRROR_CALLS).anySatisfy(call -> assertThat(call)
			.startsWith("PUT /administration/api/practitioner-tasks/" + id)
			.contains("\"assignedOrganizationId\":\"org-1\"").contains("\"assignedPractitionerId\":null")
			.contains("\"createdByOrganizationId\":\"org-1\""));
		assertThat(MIRROR_CALLS).anySatisfy(call -> assertThat(call)
			.startsWith("PUT /administration/api/practitioner-tasks/" + id)
			.contains("\"assignedPractitionerId\":\"pr-9\"").contains("\"assignedOrganizationId\":\"org-1\"")
			.contains("\"practitionerId\":\"pr-9\""));
	}

	@Test
	void taskForAPatientThatDoesNotExistInTheTenant_isRejected() {
		asServiceAccount("pr-admin");
		Task task = new Task();
		task.setStatus(Task.TaskStatus.REQUESTED);
		task.setIntent(Task.TaskIntent.ORDER);
		task.setFor(new Reference("Patient/does-not-exist"));

		assertThatThrownBy(() -> tenantClient.create().resource(task).execute())
			.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void userWithoutProvenanceWritePermission_cannotForgeProvenanceThroughTheApi() {
		asHuman("pr-7", List.of("Task.rscud", "Provenance.rs"));

		Provenance forged = new Provenance();
		forged.addTarget(new Reference("Task/whatever"));

		assertThatThrownBy(() -> tenantClient.create().resource(forged).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void readingClinicalData_leavesAnAuditEventNamingWhoReadWhat() {
		asServiceAccount("pr-admin");
		Patient patient = new Patient();
		patient.addName().setFamily("Doe");
		String patientId = tenantClient.create().resource(patient).execute().getId().getIdPart();

		asHuman("pr-7", List.of("Patient.rs"));
		tenantClient.read().resource(Patient.class).withId(new IdType("Patient", patientId)).execute();

		asServiceAccount("pr-admin");
		await().untilAsserted(() -> {
			Bundle bundle = tenantClient.search().forResource(AuditEvent.class).returnBundle(Bundle.class).execute();
			List<AuditEvent> events = bundle.getEntry().stream().map(e -> (AuditEvent) e.getResource()).toList();
			assertThat(events).anySatisfy(ev -> {
				assertThat(ev.getAgentFirstRep().getWho().getReference()).isEqualTo("Practitioner/pr-7");
				assertThat(ev.getEntity()).extracting(en -> en.getWhat().getReference()).contains("Patient/" + patientId);
			});
		});
	}
}
