package org.farmsuite.fhir;

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.farmsuite.fhir.consent.SubjectKind;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.MedicationRequest;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Practitioner;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un externo (médico autónomo, farmacia) en el tenant {@code farm-c}, al que entra solo por un Consent y
 * no por su sesión: qué puede ver, escribir y borrar. Su sesión es la de otro tenant ({@code farm-a}) y
 * sin organización, que es justo el hueco que no puede abrirse: se decide por el Consent, no por la
 * organización ni por "no sé quién es".
 *
 * <p>Datos de {@code farm-c}: personas {@code P1} (cubierta) y {@code P2} (no), un animal {@code A},
 * una observación de cada uno, y dos tareas sobre P1: una de {@code pr-ext-c} y otra de otro profesional.
 * {@code pr-ext-c} tiene un Consent de solo lectura sobre P1; {@code pr-ext-w}, uno de escritura de
 * Observation sobre P1.
 */
class ExternalConsentAccessIT extends AbstractFarmSuiteServerIT {

	private static final String TENANT_C = "farm-c";
	private static final List<String> EVERYTHING = List.of("*.rscud");
	private static boolean created;

	private static String p1;
	private static String p2;
	private static String animal;
	private static String observationOfP1;
	private static String observationOfP2;
	private static String observationOfAnimal;
	private static String taskOfExternal;
	private static String taskOfSomeoneElse;

	@BeforeEach
	void createTenantC() {
		if (created) return;
		asServiceAccount("pr-admin");
		createPartition(9, TENANT_C);
		for (String id : List.of("pr-ext-c", "pr-ext-w", "pr-ext-none")) {
			Practitioner practitioner = new Practitioner();
			practitioner.setId(id);
			practitioner.addName().setFamily(id);
			tenantClient.update().resource(practitioner).execute();
		}

		Organization organization = new Organization();
		organization.setId("org-c");
		organization.setName("Granja C");
		clientFor(TENANT_C).update().resource(organization).execute();

		p1 = person("P1");
		p2 = person("P2");
		Patient cow = new Patient();
		cow.getMeta().addProfile(SubjectKind.ANIMAL_PROFILE);
		cow.addName().setFamily("Vaca");
		cow.setManagingOrganization(new Reference("Organization/org-c"));
		animal = clientFor(TENANT_C).create().resource(cow).execute().getId().getIdPart();

		observationOfP1 = clientFor(TENANT_C).create().resource(observation(p1)).execute().getId().getIdPart();
		observationOfP2 = clientFor(TENANT_C).create().resource(observation(p2)).execute().getId().getIdPart();
		observationOfAnimal = clientFor(TENANT_C).create().resource(observation(animal)).execute().getId().getIdPart();

		grant("Practitioner/pr-ext-c", p1, null, null);
		grant("Practitioner/pr-ext-w", p1, "write", "Observation");

		taskOfExternal = clientFor(TENANT_C).create().resource(task("Practitioner/pr-ext-c", p1)).execute().getId().getIdPart();
		taskOfSomeoneElse = clientFor(TENANT_C).create().resource(task("Practitioner/pr-9", p1)).execute().getId().getIdPart();
		created = true;
	}

	@Test
	void anExternalWithAReadConsent_readsTheCoveredPersonAndTheirObservations() {
		asHuman("pr-ext-c", null, EVERYTHING);

		assertThatCode(() -> clientFor(TENANT_C).read().resource(Patient.class).withId(new IdType("Patient", p1)).execute()).doesNotThrowAnyException();
		assertThatCode(() -> clientFor(TENANT_C).read().resource(Observation.class).withId(new IdType("Observation", observationOfP1)).execute())
			.doesNotThrowAnyException();
		List<Observation> ofP1 = searchObservationsOf(p1);
		assertThat(ofP1).extracting(o -> o.getIdElement().getIdPart()).contains(observationOfP1);
		assertThat(ofP1).allSatisfy(o -> assertThat(o.getSubject().getReference()).isEqualTo("Patient/" + p1));

		// y una búsqueda sin filtrar tampoco deja ver las de la persona no cubierta ni las del animal
		Bundle all = clientFor(TENANT_C).search().forResource(Observation.class).returnBundle(Bundle.class).execute();
		assertThat(all.getEntry()).isNotEmpty().allSatisfy(e ->
			assertThat(((Observation) e.getResource()).getSubject().getReference()).isEqualTo("Patient/" + p1));
	}

	@Test
	void anExternal_doesNotSeeAPersonNoConsentCovers_norTheirObservations() {
		asHuman("pr-ext-c", null, EVERYTHING);

		assertThatThrownBy(() -> clientFor(TENANT_C).read().resource(Patient.class).withId(new IdType("Patient", p2)).execute())
			.isInstanceOf(BaseServerResponseException.class);
		assertThatThrownBy(() -> clientFor(TENANT_C).read().resource(Observation.class).withId(new IdType("Observation", observationOfP2)).execute())
			.isInstanceOf(BaseServerResponseException.class);
		assertThat(searchObservationsOf(p2)).isEmpty();
	}

	@Test
	void anAnimalNeedsAConsentToo_forSomeoneWhoDoesNotBelongToTheTenant() {
		asHuman("pr-ext-c", null, EVERYTHING);

		assertThatThrownBy(() -> clientFor(TENANT_C).read().resource(Observation.class).withId(new IdType("Observation", observationOfAnimal)).execute())
			.isInstanceOf(BaseServerResponseException.class);
		assertThat(searchObservationsOf(animal)).isEmpty();
	}

	@Test
	void anExternalWithoutAnyConsentInTheTenant_doesNotEvenGetIn() {
		asHuman("pr-ext-none", null, EVERYTHING);

		assertThatThrownBy(() -> clientFor(TENANT_C).read().resource(Patient.class).withId(new IdType("Patient", p1)).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void anExternal_doesNotReadWhatIsNotLinkedToAPatient() {
		asHuman("pr-ext-c", null, EVERYTHING);

		assertThatThrownBy(() -> clientFor(TENANT_C).read().resource(Organization.class).withId("org-c").execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aReadOnlyConsent_doesNotAllowWriting() {
		asHuman("pr-ext-c", null, EVERYTHING);

		assertThatThrownBy(() -> clientFor(TENANT_C).create().resource(observation(p1)).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aWriteConsent_allowsCreatingTheCoveredTypeForTheCoveredPerson_andNothingElse() {
		asHuman("pr-ext-w", null, EVERYTHING);

		assertThatCode(() -> clientFor(TENANT_C).create().resource(observation(p1)).execute()).doesNotThrowAnyException();

		// otro tipo de recurso del mismo paciente, que el Consent no cubre
		MedicationRequest prescription = new MedicationRequest();
		prescription.setStatus(MedicationRequest.MedicationrequestStatus.ACTIVE);
		prescription.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
		prescription.setSubject(new Reference("Patient/" + p1));
		assertThatThrownBy(() -> clientFor(TENANT_C).create().resource(prescription).execute()).isInstanceOf(ForbiddenOperationException.class);

		// el mismo tipo, pero de una persona que no cubre
		assertThatThrownBy(() -> clientFor(TENANT_C).create().resource(observation(p2)).execute()).isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aWriteConsent_neverAllowsDeleting() {
		asHuman("pr-ext-w", null, EVERYTHING);

		assertThatThrownBy(() -> clientFor(TENANT_C).delete().resourceById("Observation", observationOfP1).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void anExternal_seesAndUpdatesTheTasksAssignedToThem_butNotTheRest() {
		asHuman("pr-ext-c", null, EVERYTHING);

		Task mine = clientFor(TENANT_C).read().resource(Task.class).withId(new IdType("Task", taskOfExternal)).execute();
		assertThatThrownBy(() -> clientFor(TENANT_C).read().resource(Task.class).withId(new IdType("Task", taskOfSomeoneElse)).execute())
			.isInstanceOf(BaseServerResponseException.class);
		Bundle visible = clientFor(TENANT_C).search().forResource(Task.class).returnBundle(Bundle.class).execute();
		assertThat(visible.getEntry()).hasSize(1);

		mine.setStatus(Task.TaskStatus.ACCEPTED);
		assertThatCode(() -> clientFor(TENANT_C).update().resource(mine).execute()).doesNotThrowAnyException();
	}

	@Test
	void anExternal_cannotCreateTasks_norDeleteTheirOwn_norTakeSomeoneElses() {
		asHuman("pr-ext-c", null, EVERYTHING);

		assertThatThrownBy(() -> clientFor(TENANT_C).create().resource(task("Practitioner/pr-ext-c", p1)).execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> clientFor(TENANT_C).delete().resourceById("Task", taskOfExternal).execute())
			.isInstanceOf(ForbiddenOperationException.class);

		Task someoneElses = new Task();
		someoneElses.setId(taskOfSomeoneElse);
		someoneElses.setStatus(Task.TaskStatus.ACCEPTED);
		someoneElses.setIntent(Task.TaskIntent.ORDER);
		someoneElses.setOwner(new Reference("Practitioner/pr-ext-c"));
		someoneElses.setFor(new Reference("Patient/" + p1));
		assertThatThrownBy(() -> clientFor(TENANT_C).update().resource(someoneElses).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	private List<Observation> searchObservationsOf(String patientId) {
		Bundle bundle = clientFor(TENANT_C).search().forResource(Observation.class)
			.where(Observation.SUBJECT.hasId("Patient/" + patientId)).returnBundle(Bundle.class).execute();
		return bundle.getEntry().stream().map(e -> (Observation) e.getResource()).toList();
	}

	private String person(String family) {
		Patient patient = new Patient();
		patient.addName().setFamily(family);
		patient.setManagingOrganization(new Reference("Organization/org-c"));
		return clientFor(TENANT_C).create().resource(patient).execute().getId().getIdPart();
	}

	private static Observation observation(String patientId) {
		Observation observation = new Observation();
		observation.setStatus(Enumerations.ObservationStatus.FINAL);
		observation.getCode().setText("Peso");
		observation.setSubject(new Reference("Patient/" + patientId));
		return observation;
	}

	private static Task task(String owner, String patientId) {
		Task task = new Task();
		task.setStatus(Task.TaskStatus.REQUESTED);
		task.setIntent(Task.TaskIntent.ORDER);
		task.setOwner(new Reference(owner));
		task.setFor(new Reference("Patient/" + patientId));
		return task;
	}

	/** Un Consent activo en farm-c a favor de {@code grantee}, sobre {@code patientId}; acción y tipo opcionales. */
	private void grant(String grantee, String patientId, String action, String resourceType) {
		Consent consent = new Consent();
		consent.setStatus(Consent.ConsentState.ACTIVE);
		consent.setDecision(Enumerations.ConsentProvisionType.PERMIT);
		consent.setSubject(new Reference("Patient/" + patientId));
		consent.addGrantee(new Reference(grantee));
		if (action != null || resourceType != null) {
			Consent.ProvisionComponent provision = consent.addProvision();
			if (action != null) provision.addAction(new CodeableConcept().addCoding(new Coding().setCode(action)));
			if (resourceType != null) provision.addResourceType(new Coding().setCode(resourceType));
		}
		clientFor(TENANT_C).create().resource(consent).execute();
	}
}
