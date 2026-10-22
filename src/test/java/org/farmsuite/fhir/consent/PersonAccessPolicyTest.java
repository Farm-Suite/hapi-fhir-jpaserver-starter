package org.farmsuite.fhir.consent;

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
	private static final ConsentLookup NO_CONSENT = (p, o, t, n) -> false;
	private static final ConsentLookup ALWAYS_CONSENT = (p, o, t, n) -> true;

	private static PatientFacts person(String managingOrg) {
		return new PatientFacts("p1", SubjectKind.PERSON, managingOrg);
	}

	@Test
	void animalsAndPlants_areGovernedByPermissionsNotConsent() {
		for (SubjectKind kind : new SubjectKind[] {SubjectKind.ANIMAL, SubjectKind.PLANT}) {
			assertThat(PersonAccessPolicy.decide(new PatientFacts("a1", kind, "org-1"), "org-other", "Observation", ONLY_SELF, NO_CONSENT, NOW))
				.isEqualTo(Decision.ALLOW_NOT_A_PERSON);
		}
	}

	@Test
	void managingOrganization_accessesByCareRelationshipWithoutConsent() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), "org-1", "Observation", ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_CARE_RELATIONSHIP);
	}

	@Test
	void suborganizationOfTheManagingOne_alsoAccessesByCareRelationship() {
		Hierarchy childOfOrg1 = (org, ancestor) -> org.equals(ancestor) || (org.equals("org-1-sub") && ancestor.equals("org-1"));

		assertThat(PersonAccessPolicy.decide(person("org-1"), "org-1-sub", "Observation", childOfOrg1, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_CARE_RELATIONSHIP);
	}

	@Test
	void externalOrganizationWithoutConsent_isDenied() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), "pharmacy-ext", "MedicationRequest", ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.DENY);
	}

	@Test
	void externalOrganizationWithAnActiveConsent_isAllowed() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), "pharmacy-ext", "MedicationRequest", ONLY_SELF, ALWAYS_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_CONSENT);
	}

	@Test
	void theConsentLookupReceivesWhatIsNeededToScopeIt() {
		ConsentLookup scoped = (patient, org, type, now) ->
			patient.equals("p1") && org.equals("pharmacy-ext") && type.equals("MedicationRequest") && now.equals(NOW);

		assertThat(PersonAccessPolicy.decide(person("org-1"), "pharmacy-ext", "MedicationRequest", ONLY_SELF, scoped, NOW))
			.isEqualTo(Decision.ALLOW_CONSENT);
		assertThat(PersonAccessPolicy.decide(person("org-1"), "pharmacy-ext", "Observation", ONLY_SELF, scoped, NOW))
			.isEqualTo(Decision.DENY);
	}

	@Test
	void sessionWithoutOrganization_orPersonWithoutManagingOrganization_cannotBeClassified_andIsAllowed() {
		assertThat(PersonAccessPolicy.decide(person("org-1"), null, "Observation", ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT);
		assertThat(PersonAccessPolicy.decide(person("org-1"), " ", "Observation", ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT);
		assertThat(PersonAccessPolicy.decide(person(null), "pharmacy-ext", "Observation", ONLY_SELF, NO_CONSENT, NOW))
			.isEqualTo(Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT);
	}
}
