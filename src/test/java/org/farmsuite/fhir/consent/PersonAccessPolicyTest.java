package org.farmsuite.fhir.consent;

import org.farmsuite.fhir.consent.ConsentMatcher.Access;
import org.farmsuite.fhir.consent.ConsentMatcher.Actor;
import org.farmsuite.fhir.consent.PersonAccessPolicy.ConsentLookup;
import org.farmsuite.fhir.consent.PersonAccessPolicy.Decision;
import org.farmsuite.fhir.consent.PersonAccessPolicy.Hierarchy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** La regla RGPD de acceso a datos de personas, caso por caso. */
class PersonAccessPolicyTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
	private static final Hierarchy ONLY_SELF = (org, ancestor) -> org.equals(ancestor);
	private static final ConsentLookup NO_CONSENT = (p, a, t, m, n) -> false;
	private static final ConsentLookup ALWAYS_CONSENT = (p, a, t, m, n) -> true;

	private static PatientFacts person(String managingOrg) {
		return new PatientFacts("p1", SubjectKind.PERSON, managingOrg);
	}

	@Test
	void animalsAndPlants_areGovernedByPermissionsNotConsent() {
		for (SubjectKind kind : new SubjectKind[] {SubjectKind.ANIMAL, SubjectKind.PLANT}) {
			assertThat(PersonAccessPolicy.decide(new PatientFacts("a1", kind, "org-1"), new Actor(null, "org-other"), "Observation", Access.READ, ONLY_SELF, NO_CONSENT, NOW))
				.isEqualTo(Decision.ALLOW_NOT_A_PERSON);
		}
	}

	@Test
	void managingOrganization_accessesByCareRelationshipWithoutConsent() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, "org-1"), "Observation", Access.READ, ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_CARE_RELATIONSHIP);
	}

	@Test
	void suborganizationOfTheManagingOne_alsoAccessesByCareRelationship() {
		Hierarchy childOfOrg1 = (org, ancestor) -> org.equals(ancestor) || (org.equals("org-1-sub") && ancestor.equals("org-1"));

		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, "org-1-sub"), "Observation", Access.READ, childOfOrg1, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_CARE_RELATIONSHIP);
	}

	@Test
	void externalOrganizationWithoutConsent_isDenied() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, "pharmacy-ext"), "MedicationRequest", Access.READ, ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.DENY);
	}

	@Test
	void externalOrganizationWithAnActiveConsent_isAllowed() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, "pharmacy-ext"), "MedicationRequest", Access.READ, ONLY_SELF, ALWAYS_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_CONSENT);
	}

	@Test
	void theConsentLookupReceivesWhatIsNeededToScopeIt() {
		ConsentLookup scoped = (patient, actor, type, access, now) ->
			patient.equals("p1") && "pharmacy-ext".equals(actor.organizationId()) && type.equals("MedicationRequest")
				&& access == Access.READ && now.equals(NOW);

		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, "pharmacy-ext"), "MedicationRequest", Access.READ, ONLY_SELF, scoped, NOW))
			.isEqualTo(Decision.ALLOW_CONSENT);
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, "pharmacy-ext"), "Observation", Access.READ, ONLY_SELF, scoped, NOW))
			.isEqualTo(Decision.DENY);
	}

	@Test
	void theConsentLookupReceivesThePractitionerAndTheRequestedAccess() {
		ConsentLookup onlyWriting = (patient, actor, type, access, now) ->
			"dr-1".equals(actor.practitionerId()) && access == Access.WRITE;

		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor("dr-1", "pharmacy-ext"), "Observation", Access.WRITE, ONLY_SELF, onlyWriting, NOW))
			.isEqualTo(Decision.ALLOW_CONSENT);
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor("dr-1", "pharmacy-ext"), "Observation", Access.READ, ONLY_SELF, onlyWriting, NOW))
			.isEqualTo(Decision.DENY);
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor("dr-2", "pharmacy-ext"), "Observation", Access.WRITE, ONLY_SELF, onlyWriting, NOW))
			.isEqualTo(Decision.DENY);
	}

	@Test
	void anExternal_isDecidedOnlyByTheConsent_whateverTheKindOfPatientOrTheOrganization() {
		for (SubjectKind kind : SubjectKind.values()) {
			PatientFacts patient = new PatientFacts("p1", kind, "org-1");
			// sin Consent no se abre nada: ni un animal o un árbol, ni con la "misma" organización gestora
			assertThat(PersonAccessPolicy.decideForExternal(patient, new Actor("dr-1", "org-1"), "Observation", Access.READ, NO_CONSENT, NOW))
				.isEqualTo(Decision.DENY);
			assertThat(PersonAccessPolicy.decideForExternal(patient, new Actor("dr-1", null), "Observation", Access.READ, ALWAYS_CONSENT, NOW))
				.isEqualTo(Decision.ALLOW_CONSENT);
		}
	}

	@Test
	void anExternal_withAPatientThatCannotBeRead_isDenied_evenWithAConsent() {
		assertThat(PersonAccessPolicy.decideForExternal(PatientFacts.unresolved("p1"), new Actor("dr-1", null), "Observation", Access.READ, ALWAYS_CONSENT, NOW))
			.isEqualTo(Decision.DENY);
	}

	@Test
	void anExternal_passesTheActorTheTypeAndTheAccessToTheConsentLookup() {
		ConsentLookup onlyWritingObservations = (patient, actor, type, access, now) ->
			"dr-1".equals(actor.practitionerId()) && type.equals("Observation") && access == Access.WRITE;

		assertThat(PersonAccessPolicy.decideForExternal(person("org-1"), new Actor("dr-1", null), "Observation", Access.WRITE, onlyWritingObservations, NOW))
			.isEqualTo(Decision.ALLOW_CONSENT);
		assertThat(PersonAccessPolicy.decideForExternal(person("org-1"), new Actor("dr-1", null), "Observation", Access.READ, onlyWritingObservations, NOW))
			.isEqualTo(Decision.DENY);
		assertThat(PersonAccessPolicy.decideForExternal(person("org-1"), new Actor("dr-1", null), "Condition", Access.WRITE, onlyWritingObservations, NOW))
			.isEqualTo(Decision.DENY);
	}

	@Test
	void sessionWithoutOrganization_orPersonWithoutManagingOrganization_cannotBeClassified_andIsAllowed() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, null), "Observation", Access.READ, ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT);
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, " "), "Observation", Access.READ, ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT);
		assertThat(PersonAccessPolicy.decide(person(null), new Actor(null, "pharmacy-ext"), "Observation", Access.READ, ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT);
	}

	@Test
	void requireOrganization_deniesASessionWithoutOrganization_butNotOneThatHasIt() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, null), "Observation", Access.READ, ONLY_SELF, ALWAYS_CONSENT, NOW, true))
			.isEqualTo(Decision.DENY);
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, " "), "Observation", Access.READ, ONLY_SELF, ALWAYS_CONSENT, NOW, true))
			.isEqualTo(Decision.DENY);
		assertThat(PersonAccessPolicy.decide(person("org-1"), new Actor(null, "org-1"), "Observation", Access.READ, ONLY_SELF, NO_CONSENT, NOW, true))
			.isEqualTo(Decision.ALLOW_CARE_RELATIONSHIP);
	}

	@Test
	void requireOrganization_doesNotAffectAnimalsOrPlants() {
		assertThat(PersonAccessPolicy.decide(new PatientFacts("a1", SubjectKind.ANIMAL, "org-1"), new Actor(null, null), "Observation", Access.READ, ONLY_SELF, NO_CONSENT, NOW, true))
			.isEqualTo(Decision.ALLOW_NOT_A_PERSON);
	}

	@Test
	void aPatientThatCouldNotBeRead_isDeniedEvenToItsManagingOrganization() {
		assertThat(PersonAccessPolicy.decide(PatientFacts.unresolved("p1"), new Actor(null, "org-1"), "Observation", Access.READ, ONLY_SELF, ALWAYS_CONSENT, NOW))
			.isEqualTo(Decision.DENY);
		assertThat(PersonAccessPolicy.decide(PatientFacts.unresolved("p1"), new Actor(null, null), "Observation", Access.READ, ONLY_SELF, ALWAYS_CONSENT, NOW))
			.isEqualTo(Decision.DENY);
	}
}
