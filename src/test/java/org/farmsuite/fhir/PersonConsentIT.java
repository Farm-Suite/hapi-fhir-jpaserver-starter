package org.farmsuite.fhir;

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.farmsuite.fhir.consent.SubjectKind;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Group;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * El RGPD sobre datos de personas, contra un servidor real: la organización que gestiona al paciente
 * (y sus suborganizaciones) accede por la relación asistencial; cualquier otra solo con un Consent
 * activo concedido a su organización. Animales y árboles no pasan por el Consent.
 */
class PersonConsentIT extends AbstractFarmSuiteServerIT {

	private static final List<String> CAN_READ = List.of("Patient.rs", "Observation.rs");
	private static final List<String> CAN_WRITE = List.of("Patient.rs", "Observation.rscud");
	private static final String EXTERNAL = "pharmacy-ext";

	@Test
	void managingOrganization_readsItsPersonWithoutConsent() {
		Fixture f = personWithObservation();
		asHuman("pr-7", "org-1", CAN_READ);

		assertCanRead(f);
	}

	@Test
	void suborganizationOfTheManagingOne_readsWithoutConsent() {
		Fixture f = personWithObservation();
		asHuman("pr-7", "org-1-sub", CAN_READ);

		assertCanRead(f);
	}

	@Test
	void externalOrganizationWithoutConsent_cannotReadAPerson_andSeesNothingInSearches() {
		Fixture f = personWithObservation();
		asHuman("pr-7", EXTERNAL, CAN_READ);

		assertCannotRead(f);
		assertThat(searchObservations(f)).isEmpty();
	}

	@Test
	void externalOrganizationWithAnActiveConsent_canRead() {
		Fixture f = personWithObservation();
		consent(Enumerations.ConsentProvisionType.PERMIT, Consent.ConsentState.ACTIVE, "Patient/" + f.patientId, null);
		asHuman("pr-7", EXTERNAL, CAN_READ);

		assertCanRead(f);
		assertThat(searchObservations(f)).hasSize(1);
	}

	@Test
	void aConsentGrantedToAGroupOfPeople_coversEveryoneInTheGroup_andNobodyElse() {
		Fixture inGroup = personWithObservation();
		Fixture outsideGroup = personWithObservation();
		asServiceAccount("pr-admin");
		Group group = new Group();
		group.setType(Group.GroupType.PERSON);
		group.setMembership(Group.GroupMembershipBasis.ENUMERATED);
		group.addMember().setEntity(new Reference("Patient/" + inGroup.patientId));
		String groupId = tenantClient.create().resource(group).execute().getId().getIdPart();
		consent(Enumerations.ConsentProvisionType.PERMIT, Consent.ConsentState.ACTIVE, "Group/" + groupId, null);
		asHuman("pr-7", EXTERNAL, CAN_READ);

		assertCanRead(inGroup);
		assertCannotRead(outsideGroup);
	}

	@Test
	void aRevokedConsent_deniesAgain() {
		Fixture f = personWithObservation();
		String consentId = consent(Enumerations.ConsentProvisionType.PERMIT, Consent.ConsentState.ACTIVE, "Patient/" + f.patientId, null);
		asHuman("pr-7", EXTERNAL, CAN_READ);
		assertCanRead(f);

		asServiceAccount("pr-admin");
		Consent stored = tenantClient.read().resource(Consent.class).withId(consentId).execute();
		stored.setStatus(Consent.ConsentState.INACTIVE);
		tenantClient.update().resource(stored).execute();
		asHuman("pr-7", EXTERNAL, CAN_READ);

		assertCannotRead(f);
	}

	@Test
	void aDenyConsent_neverGrantsAccess() {
		Fixture f = personWithObservation();
		consent(Enumerations.ConsentProvisionType.DENY, Consent.ConsentState.ACTIVE, "Patient/" + f.patientId, null);
		asHuman("pr-7", EXTERNAL, CAN_READ);

		assertCannotRead(f);
	}

	@Test
	void aConsentLimitedToAResourceType_onlyCoversThatType() {
		Fixture f = personWithObservation();
		consent(Enumerations.ConsentProvisionType.PERMIT, Consent.ConsentState.ACTIVE, "Patient/" + f.patientId, "Observation");
		asHuman("pr-7", EXTERNAL, CAN_READ);

		assertThat(searchObservations(f)).hasSize(1);
		assertThatThrownBy(() -> tenantClient.read().resource(Patient.class).withId(new IdType("Patient", f.patientId)).execute())
			.isInstanceOf(BaseServerResponseException.class);
	}

	@Test
	void animalsAndTrees_areReadableByAnyOrganizationWithPermissions_withoutConsent() {
		for (String profile : new String[] {SubjectKind.ANIMAL_PROFILE, SubjectKind.PLANT_PROFILE}) {
			asServiceAccount("pr-admin");
			Patient subject = new Patient();
			subject.getMeta().addProfile(profile);
			subject.addName().setFamily("Vaca 17");
			subject.setManagingOrganization(new Reference("Organization/org-1"));
			String id = tenantClient.create().resource(subject).execute().getId().getIdPart();
			asHuman("pr-7", EXTERNAL, CAN_READ);

			assertThatCode(() -> tenantClient.read().resource(Patient.class).withId(new IdType("Patient", id)).execute())
				.doesNotThrowAnyException();
		}
	}

	@Test
	void aDeniedAccess_leavesAnAuditEvent_namingWhoTriedOnBehalfOfWhichOrganization_andOnePerRequest() {
		Fixture f = personWithObservation();
		asHuman("pr-7", EXTERNAL, CAN_READ);
		assertThat(searchObservations(f)).isEmpty();
		assertThatThrownBy(() -> tenantClient.read().resource(Patient.class).withId(new IdType("Patient", f.patientId)).execute())
			.isInstanceOf(BaseServerResponseException.class);

		asServiceAccount("pr-admin");
		await().untilAsserted(() -> {
			Bundle bundle = tenantClient.search().forResource(AuditEvent.class).count(500).returnBundle(Bundle.class).execute();
			List<AuditEvent> denied = bundle.getEntry().stream().map(e -> (AuditEvent) e.getResource())
				.filter(ev -> "4".equals(ev.getOutcome().getCode().getCode()))
				.filter(ev -> ev.getEntity().stream().anyMatch(en -> en.getWhat().getReference().equals("Patient/" + f.patientId)
					|| en.getWhat().getReference().equals("Observation/" + f.observationId)))
				.toList();
			assertThat(denied).isNotEmpty();
			assertThat(denied).allSatisfy(ev -> {
				assertThat(ev.getAgentFirstRep().getWho().getReference()).isEqualTo("Practitioner/pr-7");
				assertThat(ev.getAgent()).extracting(a -> a.getWho().getReference()).contains("Organization/" + EXTERNAL);
			});
		});
	}

	@Test
	void registeringAndRevokingAConsent_leavesAProvenanceNamingWhoDidIt() {
		Fixture f = personWithObservation();
		// Quien gestiona consentimientos: de la organización gestora, con permiso solo sobre Consent.
		asHuman("pr-7", "org-1", List.of("Consent.rscu", "Provenance.rs"));
		Consent consent = new Consent();
		consent.setStatus(Consent.ConsentState.ACTIVE);
		consent.setDecision(Enumerations.ConsentProvisionType.PERMIT);
		consent.setSubject(new Reference("Patient/" + f.patientId));
		consent.addGrantee(new Reference("Organization/" + EXTERNAL));
		String id = tenantClient.create().resource(consent).execute().getId().getIdPart();

		Consent stored = tenantClient.read().resource(Consent.class).withId(id).execute();
		stored.setStatus(Consent.ConsentState.INACTIVE);
		tenantClient.update().resource(stored).execute();

		Bundle bundle = tenantClient.search().forResource(Provenance.class)
			.where(Provenance.TARGET.hasId("Consent/" + id)).returnBundle(Bundle.class).execute();
		List<Provenance> provenances = bundle.getEntry().stream().map(e -> (Provenance) e.getResource()).toList();

		assertThat(provenances).extracting(p -> p.getActivity().getCodingFirstRep().getCode()).containsExactlyInAnyOrder("grant", "revoke");
		assertThat(provenances).allSatisfy(p -> assertThat(p.getAgentFirstRep().getWho().getReference()).isEqualTo("Practitioner/pr-7"));
		assertThat(provenances).anySatisfy(p -> assertThat(p.getEntityFirstRep().getWhat().getReference()).contains("Consent/" + id));
	}

	@Test
	void externalOrganizationWithoutConsent_cannotWriteForAPerson() {
		Fixture f = personWithObservation();
		asHuman("pr-7", EXTERNAL, CAN_WRITE);

		assertThatThrownBy(() -> tenantClient.create().resource(observationFor(f)).execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> tenantClient.delete().resourceById("Observation", f.observationId).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void externalOrganizationWithAnActiveConsent_canWriteForAPerson() {
		Fixture f = personWithObservation();
		consent(Enumerations.ConsentProvisionType.PERMIT, Consent.ConsentState.ACTIVE, "Patient/" + f.patientId, null);
		asHuman("pr-7", EXTERNAL, CAN_WRITE);

		assertThatCode(() -> tenantClient.create().resource(observationFor(f)).execute()).doesNotThrowAnyException();
	}

	@Test
	void managingOrganization_canWriteForItsPersonWithoutConsent() {
		Fixture f = personWithObservation();
		asHuman("pr-7", "org-1", CAN_WRITE);

		assertThatCode(() -> tenantClient.create().resource(observationFor(f)).execute()).doesNotThrowAnyException();
	}

	@Test
	void writingForAnAnimal_isNotSubjectToConsent() {
		asServiceAccount("pr-admin");
		Patient animal = new Patient();
		animal.getMeta().addProfile(SubjectKind.ANIMAL_PROFILE);
		animal.addName().setFamily("Vaca 12");
		animal.setManagingOrganization(new Reference("Organization/org-1"));
		String animalId = tenantClient.create().resource(animal).execute().getId().getIdPart();
		asHuman("pr-7", EXTERNAL, CAN_WRITE);

		assertThatCode(() -> tenantClient.create().resource(observationFor(new Fixture(animalId, null))).execute()).doesNotThrowAnyException();
	}

	@Test
	void theServiceAccount_isNotSubjectToConsent() {
		Fixture f = personWithObservation();
		asServiceAccount("pr-admin");

		assertCanRead(f);
	}

	private static Observation observationFor(Fixture f) {
		Observation observation = new Observation();
		observation.setStatus(Enumerations.ObservationStatus.FINAL);
		observation.getCode().setText("Temperatura");
		observation.setSubject(new Reference("Patient/" + f.patientId));
		return observation;
	}

	private record Fixture(String patientId, String observationId) {
	}

	private Fixture personWithObservation() {
		asServiceAccount("pr-admin");
		Patient patient = new Patient();
		patient.addName().setFamily("Doe");
		patient.setManagingOrganization(new Reference("Organization/org-1"));
		String patientId = tenantClient.create().resource(patient).execute().getId().getIdPart();

		Observation observation = new Observation();
		observation.setStatus(Enumerations.ObservationStatus.FINAL);
		observation.getCode().setText("Peso");
		observation.setSubject(new Reference("Patient/" + patientId));
		String observationId = tenantClient.create().resource(observation).execute().getId().getIdPart();
		return new Fixture(patientId, observationId);
	}

	/** Concede (o deniega) a la farmacia externa; {@code resourceType} nulo = cubre todos los tipos. */
	private String consent(Enumerations.ConsentProvisionType decision, Consent.ConsentState status, String subject, String resourceType) {
		asServiceAccount("pr-admin");
		Consent consent = new Consent();
		consent.setStatus(status);
		consent.setDecision(decision);
		consent.setSubject(new Reference(subject));
		consent.addGrantee(new Reference("Organization/" + EXTERNAL));
		if (resourceType != null) consent.addProvision().addResourceType(new Coding().setCode(resourceType));
		return tenantClient.create().resource(consent).execute().getId().getIdPart();
	}

	private void assertCanRead(Fixture f) {
		assertThatCode(() -> tenantClient.read().resource(Patient.class).withId(new IdType("Patient", f.patientId)).execute())
			.doesNotThrowAnyException();
		assertThat(searchObservations(f)).hasSize(1);
	}

	private void assertCannotRead(Fixture f) {
		assertThatThrownBy(() -> tenantClient.read().resource(Patient.class).withId(new IdType("Patient", f.patientId)).execute())
			.isInstanceOf(BaseServerResponseException.class);
		assertThatThrownBy(() -> tenantClient.read().resource(Observation.class).withId(new IdType("Observation", f.observationId)).execute())
			.isInstanceOf(BaseServerResponseException.class);
	}

	private List<Observation> searchObservations(Fixture f) {
		Bundle bundle = tenantClient.search().forResource(Observation.class)
			.where(Observation.SUBJECT.hasId("Patient/" + f.patientId)).returnBundle(Bundle.class).execute();
		return bundle.getEntry().stream().map(e -> (Observation) e.getResource()).toList();
	}
}
