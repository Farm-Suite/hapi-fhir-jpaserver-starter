package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.farmsuite.fhir.consent.TenantRequests;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Period;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Qué cambios de un Consent dejan traza y con qué actividad: alta, revocación, modificación. */
class ConsentProvenanceInterceptorTest {

	@Test
	void anActiveNewConsent_isAGrant_andADraftIsOnlyRecorded() {
		assertThat(ConsentProvenanceInterceptor.activitiesOfCreation(consent(Consent.ConsentState.ACTIVE))).containsExactly("grant");
		assertThat(ConsentProvenanceInterceptor.activitiesOfCreation(consent(Consent.ConsentState.DRAFT))).containsExactly("record");
	}

	@Test
	void goingInactive_isARevocation() {
		assertThat(ConsentProvenanceInterceptor.activitiesOf(consent(Consent.ConsentState.ACTIVE), consent(Consent.ConsentState.INACTIVE)))
			.containsExactly("revoke");
	}

	@Test
	void backToActive_isAGrantAgain() {
		assertThat(ConsentProvenanceInterceptor.activitiesOf(consent(Consent.ConsentState.INACTIVE), consent(Consent.ConsentState.ACTIVE)))
			.containsExactly("grant");
	}

	@Test
	void changingTheContent_isAModification_evenWithoutChangingTheStatus() {
		Consent changed = consent(Consent.ConsentState.ACTIVE);
		changed.setPeriod(new Period().setEnd(new Date()));
		Consent otherGrantee = consent(Consent.ConsentState.ACTIVE);
		otherGrantee.getGrantee().clear();
		otherGrantee.addGrantee(new Reference("Organization/other"));
		Consent narrowed = consent(Consent.ConsentState.ACTIVE);
		narrowed.addProvision().addResourceType(new Coding().setCode("Observation"));

		for (Consent after : new Consent[] {changed, otherGrantee, narrowed}) {
			assertThat(ConsentProvenanceInterceptor.activitiesOf(consent(Consent.ConsentState.ACTIVE), after)).containsExactly("modify");
		}
	}

	@Test
	void revokingAndChangingTheContentAtOnce_leavesBothTraces() {
		Consent after = consent(Consent.ConsentState.INACTIVE);
		after.setPeriod(new Period().setEnd(new Date()));

		assertThat(ConsentProvenanceInterceptor.activitiesOf(consent(Consent.ConsentState.ACTIVE), after)).containsExactly("revoke", "modify");
	}

	@Test
	void nothingThatMatters_leavesNoTrace_notEvenWhenOnlyMetadataDiffers() {
		Consent before = consent(Consent.ConsentState.ACTIVE);
		before.setId("Consent/c1/_history/1");
		Consent after = consent(Consent.ConsentState.ACTIVE);
		after.setId("Consent/c1/_history/2");
		after.getMeta().setVersionId("2");

		assertThat(ConsentProvenanceInterceptor.activitiesOf(before, after)).isEmpty();
	}

	@Test
	void provenance_citesTheNewVersion_thePreviousOne_andTheActor() {
		Consent before = consent(Consent.ConsentState.ACTIVE);
		before.setId("Consent/c1/_history/1");
		Consent after = consent(Consent.ConsentState.INACTIVE);
		after.setId("Consent/c1/_history/2");

		Provenance p = ConsentProvenanceInterceptor.buildProvenance(before, after, "revoke", "pr-7", new Date());

		assertThat(p.getTargetFirstRep().getReference()).endsWith("Consent/c1/_history/2");
		assertThat(p.getActivity().getCodingFirstRep().getSystem()).isEqualTo(ConsentProvenanceInterceptor.ACTIVITY_SYSTEM);
		assertThat(p.getActivity().getCodingFirstRep().getCode()).isEqualTo("revoke");
		assertThat(p.getAgentFirstRep().getWho().getReference()).isEqualTo("Practitioner/pr-7");
		assertThat(p.getEntityFirstRep().getWhat().getReference()).endsWith("Consent/c1/_history/1");
	}

	@Test
	void provenanceOfACreation_hasNoPreviousVersion_andNoAgentWithoutActor() {
		Provenance p = ConsentProvenanceInterceptor.buildProvenance(null, consent(Consent.ConsentState.ACTIVE), "grant", null, new Date());

		assertThat(p.getEntity()).isEmpty();
		assertThat(p.getAgent()).isEmpty();
	}

	@Test
	void failOpen_swallowsAWriteFailure_andFailClosed_rejectsTheChange() {
		assertThatCode(() -> interceptorWhereDaoFails(false).onCreated(consent(Consent.ConsentState.ACTIVE), mock(RequestDetails.class), RequestPartitionId.fromPartitionName("farm-a")))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> interceptorWhereDaoFails(true).onCreated(consent(Consent.ConsentState.ACTIVE), mock(RequestDetails.class), RequestPartitionId.fromPartitionName("farm-a")))
			.isInstanceOf(InternalErrorException.class);
	}

	@Test
	void failClosed_rejectsWhenTheTenantCannotBeResolved() {
		assertThatThrownBy(() -> interceptorWhereDaoFails(true).onCreated(consent(Consent.ConsentState.ACTIVE), null, null))
			.isInstanceOf(InternalErrorException.class);
	}

	private static ConsentProvenanceInterceptor interceptorWhereDaoFails(boolean failClosed) {
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		when(daoRegistry.getResourceDao(Provenance.class)).thenThrow(new IllegalStateException("db down"));
		UserSessionRepository sessions = mock(UserSessionRepository.class);
		when(sessions.getUserSession()).thenReturn(Optional.empty());
		TenantRequests tenantRequests = mock(TenantRequests.class);
		return new ConsentProvenanceInterceptor(daoRegistry, tenantRequests, sessions, failClosed);
	}

	private static Consent consent(Consent.ConsentState status) {
		Consent consent = new Consent();
		consent.setId("Consent/c1");
		consent.setStatus(status);
		consent.setDecision(Enumerations.ConsentProvisionType.PERMIT);
		consent.setSubject(new Reference("Patient/p1"));
		consent.addGrantee(new Reference("Organization/pharm"));
		return consent;
	}
}
