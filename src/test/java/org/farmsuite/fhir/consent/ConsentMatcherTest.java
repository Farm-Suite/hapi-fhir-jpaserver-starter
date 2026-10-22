package org.farmsuite.fhir.consent;

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

	private static Date date(String iso) {
		return Date.from(Instant.parse(iso));
	}
}
