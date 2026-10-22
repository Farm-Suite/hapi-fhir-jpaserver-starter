package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.IPreResourceShowDetails;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.farmsuite.fhir.consent.PatientFacts;
import org.farmsuite.fhir.consent.PatientSubjectResolver;
import org.farmsuite.fhir.consent.SubjectKind;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test puro del contenido del AuditEvent de acceso clínico. */
class ClinicalAccessAuditInterceptorTest {

	@Test
	void singleResource_isReadByActor() {
		AuditEvent e = ClinicalAccessAuditInterceptor.buildAuditEvent(List.of("Patient/p1"), "pr-1", new Date());

		assertThat(e.getCode().getCodingFirstRep().getCode()).isEqualTo("read");
		assertThat(e.getAction()).isEqualTo(AuditEvent.AuditEventAction.R);
		assertThat(e.getAgentFirstRep().getWho().getReference()).isEqualTo("Practitioner/pr-1");
		assertThat(e.getAgentFirstRep().getRequestor()).isTrue();
		assertThat(e.getEntityFirstRep().getWhat().getReference()).isEqualTo("Patient/p1");
	}

	@Test
	void severalResources_isSearch_andEntitiesAreCapped() {
		List<String> refs = IntStream.range(0, 250).mapToObj(i -> "Observation/o" + i).toList();

		AuditEvent e = ClinicalAccessAuditInterceptor.buildAuditEvent(refs, "pr-1", new Date());

		assertThat(e.getCode().getCodingFirstRep().getCode()).isEqualTo("search");
		assertThat(e.getEntity()).hasSize(ClinicalAccessAuditInterceptor.MAX_ENTITIES);
		assertThat(e.getOutcome().getDetailFirstRep().getText()).isEqualTo("250 recursos devueltos");
	}

	@Test
	void withoutActor_recordsServiceAccountDisplayInsteadOfPractitioner() {
		AuditEvent e = ClinicalAccessAuditInterceptor.buildAuditEvent(List.of("Patient/p1"), null, new Date());

		assertThat(e.getAgentFirstRep().getWho().hasReference()).isFalse();
		assertThat(e.getAgentFirstRep().getWho().getDisplay()).contains("cuenta de servicio");
	}

	@Test
	void aDeniedAccess_isRecordedAsAFailureWithTheOrganizationItActedFor() {
		AuditEvent e = ClinicalAccessAuditInterceptor.buildDeniedAuditEvent(List.of("Patient/p1", "Observation/o1"), "pr-7", "pharmacy-ext", new Date());

		assertThat(e.getOutcome().getCode().getCode()).isEqualTo("4");
		assertThat(e.getSeverity()).isEqualTo(AuditEvent.AuditEventSeverity.WARNING);
		assertThat(e.getAgentFirstRep().getWho().getReference()).isEqualTo("Practitioner/pr-7");
		assertThat(e.getAgent()).hasSize(2);
		assertThat(e.getAgent().get(1).getWho().getReference()).isEqualTo("Organization/pharmacy-ext");
		assertThat(e.getAgent().get(1).getRoleFirstRep().getText()).isEqualTo(ClinicalAccessAuditInterceptor.ON_BEHALF_OF_ROLE);
		assertThat(e.getAgent().get(1).getRequestor()).isFalse();
		assertThat(e.getEntity()).extracting(en -> en.getWhat().getReference()).containsExactly("Patient/p1", "Observation/o1");
		assertThat(e.getOutcome().getDetailFirstRep().getText()).contains("Acceso denegado");
	}

	@Test
	void aDeniedAccessWithoutOrganization_hasNoOnBehalfOf() {
		AuditEvent e = ClinicalAccessAuditInterceptor.buildDeniedAuditEvent(List.of("Patient/p1"), "pr-7", null, new Date());

		assertThat(e.getAgent()).hasSize(1);
	}

	@Test
	void auditedTypes_coverClinicalDataButNotAdministrativeOrSelf() {
		assertThat(ClinicalAccessAuditInterceptor.AUDITED_TYPES).contains("Patient", "Observation", "MedicationDispense");
		assertThat(ClinicalAccessAuditInterceptor.AUDITED_TYPES).doesNotContain("AuditEvent", "Provenance", "Organization", "Task");
	}

	@Test
	void failOpen_byDefault_letsTheReadContinueWhenTheAuditCannotBeWritten() {
		assertThatCode(() -> interceptorWhereDaoFails(false, Optional.of(facts(SubjectKind.PERSON))).onShow(oneShownPatient(), requestDetailsOfTenant("farm-a")))
			.doesNotThrowAnyException();
	}

	@Test
	void failClosed_rejectsTheReadOfAPerson_whenTheAuditCannotBeWritten() {
		assertThatThrownBy(() -> interceptorWhereDaoFails(true, Optional.of(facts(SubjectKind.PERSON))).onShow(oneShownPatient(), requestDetailsOfTenant("farm-a")))
			.isInstanceOf(InternalErrorException.class);
	}

	@Test
	void failClosed_rejectsWhenThePatientCannotBeDetermined_beingPrudent() {
		assertThatThrownBy(() -> interceptorWhereDaoFails(true, Optional.empty()).onShow(oneShownPatient(), requestDetailsOfTenant("farm-a")))
			.isInstanceOf(InternalErrorException.class);
	}

	@Test
	void failClosed_doesNotBlockTheReadOfAnAnimalOrATree() {
		for (SubjectKind kind : new SubjectKind[] {SubjectKind.ANIMAL, SubjectKind.PLANT}) {
			assertThatCode(() -> interceptorWhereDaoFails(true, Optional.of(facts(kind))).onShow(oneShownPatient(), requestDetailsOfTenant("farm-a")))
				.doesNotThrowAnyException();
		}
	}

	@Test
	void failClosed_rejectsWhenThereIsNoTenantToWriteTheAudit() {
		assertThatThrownBy(() -> interceptorWhereDaoFails(true, Optional.of(facts(SubjectKind.PERSON))).onShow(oneShownPatient(), requestDetailsOfTenant(null)))
			.isInstanceOf(InternalErrorException.class);
	}

	@Test
	void internalReads_areNeverAudited() {
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		new ClinicalAccessAuditInterceptor(daoRegistry, mock(UserSessionRepository.class), mock(PatientSubjectResolver.class), true)
			.onShow(oneShownPatient(), new SystemRequestDetails());

		verifyNoInteractions(daoRegistry);
	}

	private static ClinicalAccessAuditInterceptor interceptorWhereDaoFails(boolean failClosed, Optional<PatientFacts> patient) {
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		when(daoRegistry.getResourceDao(AuditEvent.class)).thenThrow(new IllegalStateException("db down"));
		UserSessionRepository sessions = mock(UserSessionRepository.class);
		when(sessions.getUserSession()).thenReturn(Optional.empty());
		PatientSubjectResolver resolver = mock(PatientSubjectResolver.class);
		when(resolver.factsOf(any(), any(), any())).thenReturn(patient);
		return new ClinicalAccessAuditInterceptor(daoRegistry, sessions, resolver, failClosed);
	}

	private static PatientFacts facts(SubjectKind kind) {
		return new PatientFacts("p1", kind, "org-1");
	}

	private static IPreResourceShowDetails oneShownPatient() {
		Patient patient = new Patient();
		patient.setId("Patient/p1");
		IPreResourceShowDetails details = mock(IPreResourceShowDetails.class);
		when(details.size()).thenReturn(1);
		when(details.getResource(0)).thenReturn(patient);
		return details;
	}

	private static RequestDetails requestDetailsOfTenant(String tenant) {
		RequestDetails requestDetails = mock(RequestDetails.class);
		when(requestDetails.getTenantId()).thenReturn(tenant);
		return requestDetails;
	}
}
