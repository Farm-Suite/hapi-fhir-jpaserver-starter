package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.farmsuite.fhir.consent.PatientFacts;
import org.farmsuite.fhir.consent.PatientSubjectResolver;
import org.farmsuite.fhir.consent.SubjectKind;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit test puro de qué cambios generan Provenance y de su contenido (actor, objetivo, versión previa). */
class TaskProvenanceInterceptorTest {

	@Test
	void noOwnerOrStatusChange_noActivity() {
		assertThat(TaskProvenanceInterceptor.activitiesOf(task("requested", "Practitioner/a"), task("requested", "Practitioner/a"))).isEmpty();
	}

	@Test
	void firstAssignment_isAssign() {
		assertThat(TaskProvenanceInterceptor.activitiesOf(task("requested", null), task("requested", "Organization/org")))
			.containsExactly("assign");
	}

	@Test
	void organizationToPractitioner_isDelegate() {
		assertThat(TaskProvenanceInterceptor.activitiesOf(task("requested", "Organization/org"), task("requested", "Practitioner/a")))
			.containsExactly("delegate");
	}

	@Test
	void organizationToPractitionerRole_isDelegate() {
		assertThat(TaskProvenanceInterceptor.activitiesOf(task("requested", "Organization/org"), task("requested", "PractitionerRole/r1")))
			.containsExactly("delegate");
	}

	@Test
	void practitionerToPractitioner_isReassignNotDelegate() {
		assertThat(TaskProvenanceInterceptor.activitiesOf(task("requested", "Practitioner/a"), task("requested", "Practitioner/b")))
			.containsExactly("assign");
	}

	@Test
	void removingOwner_isUnassign() {
		assertThat(TaskProvenanceInterceptor.activitiesOf(task("requested", "Practitioner/a"), task("requested", null)))
			.containsExactly("unassign");
	}

	@Test
	void statusChange_isStatusChange_andCombinesWithOwnerChange() {
		assertThat(TaskProvenanceInterceptor.activitiesOf(task("requested", "Practitioner/a"), task("accepted", "Practitioner/a")))
			.containsExactly("status-change");
		assertThat(TaskProvenanceInterceptor.activitiesOf(task("requested", "Organization/org"), task("accepted", "Practitioner/a")))
			.containsExactly("delegate", "status-change");
	}

	@Test
	void provenance_targetsNewVersion_citesPreviousVersionAndActor() {
		Task oldTask = task("requested", "Organization/org");
		oldTask.setId("Task/t1/_history/1");
		Task newTask = task("requested", "Practitioner/a");
		newTask.setId("Task/t1/_history/2");

		Provenance p = TaskProvenanceInterceptor.buildProvenance(oldTask, newTask, "delegate", "pr-7", new Date());

		assertThat(p.getTargetFirstRep().getReference()).endsWith("Task/t1/_history/2");
		assertThat(p.getActivity().getCodingFirstRep().getCode()).isEqualTo("delegate");
		assertThat(p.getActivity().getCodingFirstRep().getSystem()).isEqualTo(TaskProvenanceInterceptor.ACTIVITY_SYSTEM);
		assertThat(p.getAgentFirstRep().getWho().getReference()).isEqualTo("Practitioner/pr-7");
		assertThat(p.getEntityFirstRep().getRole()).isEqualTo(Provenance.ProvenanceEntityRole.REVISION);
		assertThat(p.getEntityFirstRep().getWhat().getReference()).endsWith("Task/t1/_history/1");
	}

	@Test
	void creation_isCreate_andAlsoAssignWhenBornAssigned() {
		assertThat(TaskProvenanceInterceptor.activitiesOfCreation(task("requested", null))).containsExactly("create");
		assertThat(TaskProvenanceInterceptor.activitiesOfCreation(task("requested", "Organization/org")))
			.containsExactly("create", "assign");
	}

	@Test
	void provenance_ofCreation_hasNoPreviousVersionEntity() {
		Provenance p = TaskProvenanceInterceptor.buildProvenance(null, task("requested", null), "create", "pr-1", new Date());

		assertThat(p.getEntity()).isEmpty();
	}

	@Test
	void provenance_withoutActor_hasNoAgent() {
		Provenance p = TaskProvenanceInterceptor.buildProvenance(task("requested", null), task("requested", "Organization/org"), "assign", null, new Date());

		assertThat(p.getAgent()).isEmpty();
	}

	@Test
	void failOpen_byDefault_swallowsAProvenanceWriteFailure() {
		TaskProvenanceInterceptor interceptor = interceptorWhereDaoFails(false, Optional.of(facts(SubjectKind.PERSON)));

		assertThatCode(() -> interceptor.onCreated(taskForPatient(), requestDetails(), RequestPartitionId.fromPartitionName("farm-a")))
			.doesNotThrowAnyException();
	}

	@Test
	void failClosed_rejectsTheChangeOfAPersonsTask_whenProvenanceCannotBeWritten() {
		TaskProvenanceInterceptor interceptor = interceptorWhereDaoFails(true, Optional.of(facts(SubjectKind.PERSON)));

		assertThatThrownBy(() -> interceptor.onCreated(taskForPatient(), requestDetails(), RequestPartitionId.fromPartitionName("farm-a")))
			.isInstanceOf(InternalErrorException.class);
	}

	@Test
	void failClosed_rejectsWhenThePatientCannotBeDetermined_beingPrudent() {
		TaskProvenanceInterceptor interceptor = interceptorWhereDaoFails(true, Optional.empty());

		assertThatThrownBy(() -> interceptor.onCreated(taskForPatient(), requestDetails(), RequestPartitionId.fromPartitionName("farm-a")))
			.isInstanceOf(InternalErrorException.class);
	}

	@Test
	void failClosed_doesNotApplyToAnimalsOrTrees() {
		for (SubjectKind kind : new SubjectKind[] {SubjectKind.ANIMAL, SubjectKind.PLANT}) {
			TaskProvenanceInterceptor interceptor = interceptorWhereDaoFails(true, Optional.of(facts(kind)));

			assertThatCode(() -> interceptor.onCreated(taskForPatient(), requestDetails(), RequestPartitionId.fromPartitionName("farm-a")))
				.doesNotThrowAnyException();
		}
	}

	@Test
	void failClosed_doesNotApplyToAnOperationalTaskWithoutPatient() {
		TaskProvenanceInterceptor interceptor = interceptorWhereDaoFails(true, Optional.empty());

		assertThatCode(() -> interceptor.onCreated(task("requested", "Organization/org"), requestDetails(), RequestPartitionId.fromPartitionName("farm-a")))
			.doesNotThrowAnyException();
	}

	@Test
	void failClosed_rejectsAPersonsTaskWhenTheTenantCannotBeResolved() {
		TaskProvenanceInterceptor interceptor = interceptorWhereDaoFails(true, Optional.of(facts(SubjectKind.PERSON)));

		assertThatThrownBy(() -> interceptor.onCreated(taskForPatient(), null, null))
			.isInstanceOf(InternalErrorException.class);
	}

	private static TaskProvenanceInterceptor interceptorWhereDaoFails(boolean failClosed, Optional<PatientFacts> patient) {
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		when(daoRegistry.getResourceDao(Provenance.class)).thenThrow(new IllegalStateException("db down"));
		UserSessionRepository sessions = mock(UserSessionRepository.class);
		when(sessions.getUserSession()).thenReturn(Optional.empty());
		PatientSubjectResolver resolver = mock(PatientSubjectResolver.class);
		when(resolver.factsOf(any(), any(), any())).thenReturn(patient);
		return new TaskProvenanceInterceptor(daoRegistry, sessions, resolver, failClosed);
	}

	private static PatientFacts facts(SubjectKind kind) {
		return new PatientFacts("p1", kind, "org-1");
	}

	private static Task taskForPatient() {
		Task task = task("requested", "Organization/org");
		task.setFor(new Reference("Patient/p1"));
		return task;
	}

	private static RequestDetails requestDetails() {
		return mock(RequestDetails.class);
	}

	private static Task task(String status, String ownerReference) {
		Task task = new Task();
		task.setId("Task/t1");
		task.setStatus(Task.TaskStatus.fromCode(status));
		if (ownerReference != null) task.setOwner(new Reference(ownerReference));
		return task;
	}
}
