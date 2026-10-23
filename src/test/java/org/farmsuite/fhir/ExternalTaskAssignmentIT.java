package org.farmsuite.fhir;

import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Practitioner;
import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asignar una tarea a alguien de fuera del tenant ({@code farm-d}) exige antes el Consent que le deja hacerla,
 * con lo que pide el tipo de tarea según el catálogo de sistema ({@code farm-task-type}); si no, se rechaza con
 * lo que falta. Los miembros del tenant (un PractitionerRole) no lo necesitan. Las organizaciones externas
 * tienen que ser de un tipo que preste servicios externos.
 */
class ExternalTaskAssignmentIT extends AbstractFarmSuiteServerIT {

	private static final String TENANT_D = "farm-d";
	private static final String TASK_TYPES = "https://farmsuite.org/fhir/DEFAULT/CodeSystem/farm-task-type";
	private static final String ORG_TYPES = "https://farmsuite.org/fhir/DEFAULT/CodeSystem/organization-type";
	private static boolean created;

	private static String patient;
	private static String vet;
	private static String vetWithWriteConsent;
	private static String vetWithReadConsent;
	private static String vetWithAccessOnly;
	private static String pharmacyOrg;
	private static String farmTypeOrg;

	@BeforeEach
	void createTenantD() {
		if (created) return;
		asServiceAccount("pr-admin");
		createPartition(10, TENANT_D);

		clientFor("DEFAULT").update().resource(taskTypes()).execute();
		clientFor("DEFAULT").update().resource(organizationTypes()).execute();

		for (String id : List.of("vet-none", "vet-read", "vet-write", "vet-access", "member-d")) {
			Practitioner practitioner = new Practitioner();
			practitioner.setId(id);
			practitioner.addName().setFamily(id);
			tenantClient.update().resource(practitioner).execute();
		}
		vet = "vet-none";
		vetWithReadConsent = "vet-read";
		vetWithWriteConsent = "vet-write";
		vetWithAccessOnly = "vet-access";

		PractitionerRole member = new PractitionerRole();
		member.setPractitioner(new Reference("Practitioner/member-d"));
		clientFor(TENANT_D).create().resource(member).execute();

		Patient p = new Patient();
		p.addName().setFamily("Paciente D");
		patient = clientFor(TENANT_D).create().resource(p).execute().getId().getIdPart();

		// organizaciones externas: viven en otra partición (farm-a), con el tipo que dice el catálogo
		pharmacyOrg = tenantClient.create().resource(organization("Farmacia", "pharmacy")).execute().getId().getIdPart();
		farmTypeOrg = tenantClient.create().resource(organization("Otra granja", "farm")).execute().getId().getIdPart();

		consent("Practitioner/vet-read", patient, null);                       // solo lectura de todo
		consent("Practitioner/vet-write", patient, "write");                   // lectura y escritura de todo
		consent("Practitioner/vet-access", null, null);                        // acceso al tenant, sin paciente
		consent("Organization/" + pharmacyOrg, patient, "write");
		consent("Organization/" + farmTypeOrg, patient, "write");
		created = true;
	}

	@Test
	void assigningToAnExternalWithoutAConsent_isRejected_sayingWhatIsMissing() {
		asHumanOf(TENANT_D, "pr-7", "org-d", List.of("*.rscud"));

		assertThatThrownBy(() -> clientFor(TENANT_D).create().resource(task("Practitioner/" + vet, "feeding", patient)).execute())
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("falta un consentimiento")
			.hasMessageContaining("Patient (lectura)");
	}

	@Test
	void anOrdinaryTask_onlyNeedsTheConsentToReadThePatient() {
		asHumanOf(TENANT_D, "pr-7", "org-d", List.of("*.rscud"));

		assertThatCode(() -> clientFor(TENANT_D).create().resource(task("Practitioner/" + vetWithReadConsent, "feeding", patient)).execute())
			.doesNotThrowAnyException();
	}

	@Test
	void aClinicalTask_needsTheConsentToWhatTheTaskTypeWrites() {
		asHumanOf(TENANT_D, "pr-7", "org-d", List.of("*.rscud"));

		assertThatThrownBy(() -> clientFor(TENANT_D).create().resource(task("Practitioner/" + vetWithReadConsent, "clinical-visit", patient)).execute())
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("Condition (lectura y escritura)")
			.hasMessageContaining("Observation (lectura y escritura)");

		assertThatCode(() -> clientFor(TENANT_D).create().resource(task("Practitioner/" + vetWithWriteConsent, "clinical-visit", patient)).execute())
			.doesNotThrowAnyException();
	}

	@Test
	void aTaskWithoutAPatient_needsAnAccessConsentToTheTenant() {
		asHumanOf(TENANT_D, "pr-7", "org-d", List.of("*.rscud"));

		assertThatThrownBy(() -> clientFor(TENANT_D).create().resource(task("Practitioner/" + vet, "feeding", null)).execute())
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("acceso a este tenant");
		assertThatCode(() -> clientFor(TENANT_D).create().resource(task("Practitioner/" + vetWithAccessOnly, "feeding", null)).execute())
			.doesNotThrowAnyException();
	}

	@Test
	void aMemberOfTheTenant_needsNoConsent() {
		asHumanOf(TENANT_D, "pr-7", "org-d", List.of("*.rscud"));

		assertThatCode(() -> clientFor(TENANT_D).create().resource(task("Practitioner/member-d", "clinical-visit", patient)).execute())
			.doesNotThrowAnyException();
	}

	@Test
	void anExternalOrganization_mustBeOfATypeThatProvidesExternalServices() {
		asHumanOf(TENANT_D, "pr-7", "org-d", List.of("*.rscud"));

		assertThatCode(() -> clientFor(TENANT_D).create().resource(task("Organization/" + pharmacyOrg, "dispensing", patient)).execute())
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> clientFor(TENANT_D).create().resource(task("Organization/" + farmTypeOrg, "feeding", patient)).execute())
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("no es de un tipo que preste servicios externos");
	}

	@Test
	void reassigningToAnExternalWithoutAConsent_isRejected_butChangingTheStatusOfAnAssignedTaskIsNot() {
		asHumanOf(TENANT_D, "pr-7", "org-d", List.of("*.rscud"));
		String id = clientFor(TENANT_D).create().resource(task("Practitioner/member-d", "feeding", patient)).execute().getId().getIdPart();
		Task stored = clientFor(TENANT_D).read().resource(Task.class).withId(id).execute();

		Task toTheVet = stored.copy();
		toTheVet.setOwner(new Reference("Practitioner/" + vet));
		assertThatThrownBy(() -> clientFor(TENANT_D).update().resource(toTheVet).execute()).isInstanceOf(UnprocessableEntityException.class);

		Task accepted = stored.copy();
		accepted.setStatus(Task.TaskStatus.ACCEPTED);
		assertThatCode(() -> clientFor(TENANT_D).update().resource(accepted).execute()).doesNotThrowAnyException();
		assertThat(clientFor(TENANT_D).read().resource(Task.class).withId(id).execute().getStatus()).isEqualTo(Task.TaskStatus.ACCEPTED);
	}

	private static Task task(String owner, String type, String patientId) {
		Task task = new Task();
		task.setStatus(Task.TaskStatus.REQUESTED);
		task.setIntent(Task.TaskIntent.ORDER);
		task.setOwner(new Reference(owner));
		task.getCode().addCoding().setSystem(TASK_TYPES).setCode(type);
		if (patientId != null) task.setFor(new Reference("Patient/" + patientId));
		return task;
	}

	private static Organization organization(String name, String type) {
		Organization organization = new Organization();
		organization.setName(name);
		organization.addType(new CodeableConcept().addCoding(new Coding().setSystem(ORG_TYPES).setCode(type)));
		return organization;
	}

	/** Un Consent activo en farm-d a favor de {@code grantee}; sin {@code patientId} es solo de acceso al tenant. */
	private void consent(String grantee, String patientId, String action) {
		Consent consent = new Consent();
		consent.setStatus(Consent.ConsentState.ACTIVE);
		consent.setDecision(Enumerations.ConsentProvisionType.PERMIT);
		if (patientId != null) consent.setSubject(new Reference("Patient/" + patientId));
		consent.addGrantee(new Reference(grantee));
		if (action != null) consent.addProvision().addAction(new CodeableConcept().addCoding(new Coding().setCode(action)));
		clientFor(TENANT_D).create().resource(consent).execute();
	}

	/** Una copia mínima del catálogo de sistema: tres tipos de tarea con sus propiedades. */
	private static CodeSystem taskTypes() {
		CodeSystem cs = new CodeSystem();
		cs.setId("farm-task-type");
		cs.setUrl(TASK_TYPES);
		cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		cs.setContent(Enumerations.CodeSystemContentMode.COMPLETE);
		cs.addConcept().setCode("feeding");
		CodeSystem.ConceptDefinitionComponent visit = cs.addConcept().setCode("clinical-visit");
		visit.addProperty().setCode("consent-read").setValue(new CodeType("Observation"));
		visit.addProperty().setCode("consent-write").setValue(new CodeType("Observation"));
		visit.addProperty().setCode("consent-write").setValue(new CodeType("Condition"));
		cs.addConcept().setCode("dispensing").addProperty().setCode("consent-write").setValue(new CodeType("MedicationDispense"));
		return cs;
	}

	private static CodeSystem organizationTypes() {
		CodeSystem cs = new CodeSystem();
		cs.setId("organization-type");
		cs.setUrl(ORG_TYPES);
		cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		cs.setContent(Enumerations.CodeSystemContentMode.COMPLETE);
		cs.addConcept().setCode("farm");
		cs.addConcept().setCode("pharmacy").addProperty().setCode("external-service").setValue(new BooleanType(true));
		return cs;
	}
}
