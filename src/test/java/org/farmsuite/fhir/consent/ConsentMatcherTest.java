package org.farmsuite.fhir.consent;

import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Period;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué consentimientos permiten ver qué a qué organización, y cuáles nunca. */
class ConsentMatcherTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
	private static final String PHARMACY = "pharmacy-ext";

	@Test
	void activePermitToTheGranteeOrganization_permits() {
		assertThat(ConsentMatcher.permits(permit(), PHARMACY, "MedicationRequest", NOW)).isTrue();
	}

	@Test
	void anotherOrganization_isNotCovered() {
		assertThat(ConsentMatcher.permits(permit(), "other-org", "MedicationRequest", NOW)).isFalse();
		assertThat(ConsentMatcher.permits(permit(), null, "MedicationRequest", NOW)).isFalse();
	}

	@Test
	void aGranteeThatIsNotAnOrganization_doesNotCount() {
		Consent consent = permit();
		consent.getGrantee().clear();
		consent.addGrantee(new Reference("Practitioner/" + PHARMACY));

		assertThat(ConsentMatcher.permits(consent, PHARMACY, "MedicationRequest", NOW)).isFalse();
	}

	@Test
	void revokedOrNotActive_neverPermits() {
		for (Consent.ConsentState state : new Consent.ConsentState[] {
			Consent.ConsentState.INACTIVE, Consent.ConsentState.DRAFT, Consent.ConsentState.ENTEREDINERROR, Consent.ConsentState.NOTDONE}) {
			Consent consent = permit();
			consent.setStatus(state);
			assertThat(ConsentMatcher.permits(consent, PHARMACY, "MedicationRequest", NOW)).isFalse();
		}
	}

	@Test
	void denyOrNoDecision_neverPermits_ambiguityIsNotAGrant() {
		Consent deny = permit();
		deny.setDecision(Enumerations.ConsentProvisionType.DENY);
		Consent undecided = permit();
		undecided.setDecisionElement(null);

		assertThat(ConsentMatcher.permits(deny, PHARMACY, "MedicationRequest", NOW)).isFalse();
		assertThat(ConsentMatcher.permits(undecided, PHARMACY, "MedicationRequest", NOW)).isFalse();
	}

	@Test
	void outsideTheConsentPeriod_doesNotPermit() {
		Consent expired = permit();
		expired.setPeriod(new Period().setStart(date("2026-01-01T00:00:00Z")).setEnd(date("2026-06-30T00:00:00Z")));
		Consent future = permit();
		future.setPeriod(new Period().setStart(date("2026-12-01T00:00:00Z")));
		Consent current = permit();
		current.setPeriod(new Period().setStart(date("2026-01-01T00:00:00Z")).setEnd(date("2026-12-31T00:00:00Z")));

		assertThat(ConsentMatcher.permits(expired, PHARMACY, "MedicationRequest", NOW)).isFalse();
		assertThat(ConsentMatcher.permits(future, PHARMACY, "MedicationRequest", NOW)).isFalse();
		assertThat(ConsentMatcher.permits(current, PHARMACY, "MedicationRequest", NOW)).isTrue();
	}

	@Test
	void aProvisionLimitsWhichResourceTypesAreCovered() {
		Consent consent = permit();
		consent.addProvision().addResourceType(new Coding().setCode("MedicationRequest")).addResourceType(new Coding().setCode("MedicationDispense"));

		assertThat(ConsentMatcher.permits(consent, PHARMACY, "MedicationRequest", NOW)).isTrue();
		assertThat(ConsentMatcher.permits(consent, PHARMACY, "Observation", NOW)).isFalse();
	}

	@Test
	void aProvisionWithoutResourceTypes_coversAllOfThem_butItsOwnPeriodStillApplies() {
		Consent open = permit();
		open.addProvision();
		Consent closed = permit();
		closed.addProvision().setPeriod(new Period().setEnd(date("2026-01-01T00:00:00Z")));

		assertThat(ConsentMatcher.permits(open, PHARMACY, "Observation", NOW)).isTrue();
		assertThat(ConsentMatcher.permits(closed, PHARMACY, "Observation", NOW)).isFalse();
	}

	@Test
	void aPractitionerGrantee_coversThatPractitionerOnly() {
		Consent consent = permit();
		consent.getGrantee().clear();
		consent.addGrantee(new Reference("Practitioner/dr-1"));

		assertThat(ConsentMatcher.permits(consent, actor("dr-1", null), "Observation", ConsentMatcher.Access.READ, NOW)).isTrue();
		assertThat(ConsentMatcher.permits(consent, actor("dr-2", null), "Observation", ConsentMatcher.Access.READ, NOW)).isFalse();
		assertThat(ConsentMatcher.permits(consent, actor("dr-1", "other-org"), "Observation", ConsentMatcher.Access.READ, NOW)).isTrue();
	}

	@Test
	void anOrganizationGrantee_coversAnyPractitionerActingForIt() {
		assertThat(ConsentMatcher.permits(permit(), actor("dr-1", PHARMACY), "Observation", ConsentMatcher.Access.READ, NOW)).isTrue();
		assertThat(ConsentMatcher.permits(permit(), actor("dr-1", "other-org"), "Observation", ConsentMatcher.Access.READ, NOW)).isFalse();
	}

	@Test
	void withoutAnAction_theConsentIsReadOnly() {
		Consent consent = permit();
		consent.addProvision().addResourceType(new Coding().setCode("Observation"));

		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Observation", ConsentMatcher.Access.READ, NOW)).isTrue();
		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Observation", ConsentMatcher.Access.WRITE, NOW)).isFalse();
	}

	@Test
	void aConsentWithoutProvisions_neverGrantsWriting() {
		assertThat(ConsentMatcher.permits(permit(), actor(null, PHARMACY), "Observation", ConsentMatcher.Access.WRITE, NOW)).isFalse();
	}

	@Test
	void theWriteAction_permitsWritingAndAlsoReading() {
		Consent consent = permit();
		consent.addProvision().addAction(new CodeableConcept().addCoding(new Coding().setCode("write")))
			.addResourceType(new Coding().setCode("Observation"));

		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Observation", ConsentMatcher.Access.WRITE, NOW)).isTrue();
		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Observation", ConsentMatcher.Access.READ, NOW)).isTrue();
		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Condition", ConsentMatcher.Access.WRITE, NOW)).isFalse();
	}

	@Test
	void theReadAction_doesNotPermitWriting() {
		Consent consent = permit();
		consent.addProvision().addAction(new CodeableConcept().addCoding(new Coding().setCode("read")));

		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Observation", ConsentMatcher.Access.READ, NOW)).isTrue();
		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Observation", ConsentMatcher.Access.WRITE, NOW)).isFalse();
	}

	@Test
	void deleting_isNeverGrantedByAConsent() {
		Consent consent = permit();
		consent.addProvision().addAction(new CodeableConcept().addCoding(new Coding().setCode("write")));

		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Observation", ConsentMatcher.Access.DELETE, NOW)).isFalse();
	}

	@Test
	void eachProvisionCarriesItsOwnModeAndTypes() {
		Consent consent = permit();
		consent.addProvision().addAction(new CodeableConcept().addCoding(new Coding().setCode("read")))
			.addResourceType(new Coding().setCode("Observation"));
		consent.addProvision().addAction(new CodeableConcept().addCoding(new Coding().setCode("write")))
			.addResourceType(new Coding().setCode("MedicationAdministration"));

		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "Observation", ConsentMatcher.Access.WRITE, NOW)).isFalse();
		assertThat(ConsentMatcher.permits(consent, actor(null, PHARMACY), "MedicationAdministration", ConsentMatcher.Access.WRITE, NOW)).isTrue();
	}

	@Test
	void anActiveGrantToTheActor_letsThemIntoTheTenant_whateverTheTypesOrTheMode() {
		Consent readOnlyOneType = permit();
		readOnlyOneType.addProvision().addResourceType(new Coding().setCode("Observation"));
		Consent toDoctor = permit();
		toDoctor.getGrantee().clear();
		toDoctor.addGrantee(new Reference("Practitioner/dr-1"));

		assertThat(ConsentMatcher.isActiveGrant(permit(), actor(null, PHARMACY), NOW)).isTrue();
		assertThat(ConsentMatcher.isActiveGrant(readOnlyOneType, actor("dr-1", PHARMACY), NOW)).isTrue();
		assertThat(ConsentMatcher.isActiveGrant(toDoctor, actor("dr-1", null), NOW)).isTrue();
	}

	@Test
	void anActiveGrant_neverLetsInSomeoneElse_norAnInactiveOrExpiredOrDeniedOne() {
		Consent expired = permit();
		expired.setPeriod(new Period().setEnd(date("2026-01-01T00:00:00Z")));
		Consent closedProvision = permit();
		closedProvision.addProvision().setPeriod(new Period().setEnd(date("2026-01-01T00:00:00Z")));
		Consent revoked = permit();
		revoked.setStatus(Consent.ConsentState.INACTIVE);
		Consent denied = permit();
		denied.setDecision(Enumerations.ConsentProvisionType.DENY);

		assertThat(ConsentMatcher.isActiveGrant(permit(), actor("dr-1", "other-org"), NOW)).isFalse();
		assertThat(ConsentMatcher.isActiveGrant(permit(), actor(null, null), NOW)).isFalse();
		assertThat(ConsentMatcher.isActiveGrant(expired, actor(null, PHARMACY), NOW)).isFalse();
		assertThat(ConsentMatcher.isActiveGrant(closedProvision, actor(null, PHARMACY), NOW)).isFalse();
		assertThat(ConsentMatcher.isActiveGrant(revoked, actor(null, PHARMACY), NOW)).isFalse();
		assertThat(ConsentMatcher.isActiveGrant(denied, actor(null, PHARMACY), NOW)).isFalse();
		assertThat(ConsentMatcher.isActiveGrant(null, actor(null, PHARMACY), NOW)).isFalse();
	}

	@Test
	void nullConsent_doesNotPermit() {
		assertThat(ConsentMatcher.permits(null, PHARMACY, "Observation", NOW)).isFalse();
	}

	private static Consent permit() {
		Consent consent = new Consent();
		consent.setStatus(Consent.ConsentState.ACTIVE);
		consent.setDecision(Enumerations.ConsentProvisionType.PERMIT);
		consent.addGrantee(new Reference("Organization/" + PHARMACY));
		return consent;
	}

	private static ConsentMatcher.Actor actor(String practitionerId, String organizationId) {
		return new ConsentMatcher.Actor(practitionerId, organizationId);
	}

	private static Date date(String iso) {
		return Date.from(Instant.parse(iso));
	}
}
