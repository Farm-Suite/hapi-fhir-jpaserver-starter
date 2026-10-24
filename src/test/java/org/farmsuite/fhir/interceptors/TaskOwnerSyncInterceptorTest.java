package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.farmsuite.fhir.usecase.TaskAssignment;
import org.farmsuite.fhir.usecase.TaskOwnerSyncNotify;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Location;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Espejo de Task.owner → Administration-MIC: qué se manda al crear, actualizar y borrar una tarea,
 * en qué tenant, y que una tarea sin dueño no deja un espejo huérfano.
 */
class TaskOwnerSyncInterceptorTest {

	private TaskOwnerSyncNotify notify;
	private DaoRegistry daoRegistry;
	private IFhirResourceDao<Location> locationDao;
	private IFhirResourceDao<PractitionerRole> roleDao;
	private TaskOwnerSyncInterceptor interceptor;

	private final RequestPartitionId farmA = RequestPartitionId.fromPartitionName("farm-a");

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		notify = mock(TaskOwnerSyncNotify.class);
		daoRegistry = mock(DaoRegistry.class);
		locationDao = mock(IFhirResourceDao.class);
		roleDao = mock(IFhirResourceDao.class);
		when(daoRegistry.getResourceDao(Location.class)).thenReturn(locationDao);
		when(daoRegistry.getResourceDao(PractitionerRole.class)).thenReturn(roleDao);
		interceptor = new TaskOwnerSyncInterceptor(notify, daoRegistry);
	}

	private static Task task(String id, String owner) {
		Task task = new Task();
		task.setId("Task/" + id);
		if (owner != null) task.getOwner().setReference(owner);
		return task;
	}

	private static RequestDetails request(String tenant) {
		ServletRequestDetails details = new ServletRequestDetails();
		details.setTenantId(tenant);
		return details;
	}

	// --- crear ---

	@Test
	void aNewTaskWithAnOwnerIsMirroredInItsTenant() {
		Task task = task("t1", "Practitioner/p1");

		interceptor.onCreated(task, request("ignored"), farmA);

		ArgumentCaptor<TaskAssignment> assignment = ArgumentCaptor.forClass(TaskAssignment.class);
		verify(notify).sync(eq(task), eq("farm-a"), eq(null), assignment.capture());
		assertThat(assignment.getValue().practitionerId()).isEqualTo("p1");
	}

	@Test
	void aNewTaskWithoutOwnerIsNotMirroredAndDoesNotTriggerARemoval() {
		interceptor.onCreated(task("t1", null), request("farm-a"), farmA);

		verifyNoInteractions(notify);
	}

	@Test
	void onlyTasksAreMirrored() {
		interceptor.onCreated(new Observation(), request("farm-a"), farmA);
		interceptor.onUpdated(new Observation(), new Observation(), request("farm-a"), farmA);
		interceptor.onDeleted(new Observation(), request("farm-a"), farmA);

		verifyNoInteractions(notify);
	}

	@Test
	void anOrganizationOwnerIsMirroredAsAQueueOfThatOrganization() {
		Task task = task("t1", "Organization/o1");

		interceptor.onCreated(task, request("farm-a"), farmA);

		ArgumentCaptor<TaskAssignment> assignment = ArgumentCaptor.forClass(TaskAssignment.class);
		verify(notify).sync(eq(task), eq("farm-a"), eq(null), assignment.capture());
		assertThat(assignment.getValue().organizationId()).isEqualTo("o1");
		assertThat(assignment.getValue().practitionerId()).isNull();
	}

	@Test
	void aPractitionerRoleOwnerIsResolvedInTheTasksPartition() {
		PractitionerRole role = new PractitionerRole();
		role.getPractitioner().setReference("Practitioner/p1");
		role.getOrganization().setReference("Organization/o1");
		when(roleDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(role);
		Task task = task("t1", "PractitionerRole/r1");

		interceptor.onCreated(task, request("farm-a"), farmA);

		ArgumentCaptor<IdType> read = ArgumentCaptor.forClass(IdType.class);
		verify(roleDao).read(read.capture(), any(RequestDetails.class));
		assertThat(read.getValue().getIdPart()).isEqualTo("r1");
		ArgumentCaptor<TaskAssignment> assignment = ArgumentCaptor.forClass(TaskAssignment.class);
		verify(notify).sync(eq(task), eq("farm-a"), eq(null), assignment.capture());
		assertThat(assignment.getValue().practitionerId()).isEqualTo("p1");
		assertThat(assignment.getValue().organizationId()).isEqualTo("o1");
	}

	@Test
	void anOwnerOfAnUnsupportedTypeIsNotMirrored() {
		interceptor.onCreated(task("t1", "Device/d1"), request("farm-a"), farmA);

		verify(notify, never()).sync(any(), any(), any(), any());
	}

	@Test
	void theOrganizationOfTheTaskLocationIsPassedAlong() {
		Location location = new Location();
		location.getManagingOrganization().setReference("Organization/o-loc");
		when(locationDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(location);
		Task task = task("t1", "Practitioner/p1");
		task.getLocation().setReference("Location/l1");

		interceptor.onCreated(task, request("farm-a"), farmA);

		verify(notify).sync(eq(task), eq("farm-a"), eq("o-loc"), any(TaskAssignment.class));
	}

	@Test
	void ifTheLocationCannotBeReadTheTaskIsStillMirrored_withoutOrganization() {
		when(locationDao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new RuntimeException("no existe"));
		Task task = task("t1", "Practitioner/p1");
		task.getLocation().setReference("Location/l1");

		interceptor.onCreated(task, request("farm-a"), farmA);

		verify(notify).sync(eq(task), eq("farm-a"), eq(null), any(TaskAssignment.class));
	}

	@Test
	void aLocationWithoutManagingOrganizationGivesNoOrganization() {
		when(locationDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(new Location());
		Task task = task("t1", "Practitioner/p1");
		task.getLocation().setReference("Location/l1");

		interceptor.onCreated(task, request("farm-a"), farmA);

		verify(notify).sync(eq(task), eq("farm-a"), eq(null), any(TaskAssignment.class));
	}

	// --- actualizar ---

	@Test
	void anUpdatedTaskWithAnOwnerIsMirroredAgain() {
		Task after = task("t1", "Practitioner/p2");

		interceptor.onUpdated(task("t1", "Practitioner/p1"), after, request("farm-a"), farmA);

		verify(notify).sync(eq(after), eq("farm-a"), eq(null), any(TaskAssignment.class));
		verify(notify, never()).remove(any());
	}

	@Test
	void removingTheOwnerRemovesTheMirror() {
		interceptor.onUpdated(task("t1", "Practitioner/p1"), task("t1", null), request("farm-a"), farmA);

		verify(notify).remove("t1");
		verify(notify, never()).sync(any(), any(), any(), any());
	}

	// --- borrar ---

	@Test
	void aDeletedTaskRemovesItsMirror() {
		interceptor.onDeleted(task("t1", "Practitioner/p1"), request("farm-a"), farmA);

		verify(notify).remove("t1");
	}

	// --- tenant ---

	@Test
	void theTenantComesFromThePartitionAndFallsBackToTheRequest() {
		assertThat(TaskOwnerSyncInterceptor.resolveTenantId(request("from-request"), farmA)).isEqualTo("farm-a");
		assertThat(TaskOwnerSyncInterceptor.resolveTenantId(request("from-request"), null)).isEqualTo("from-request");
		assertThat(TaskOwnerSyncInterceptor.resolveTenantId(request("from-request"), RequestPartitionId.defaultPartition())).isEqualTo("from-request");
		assertThat(TaskOwnerSyncInterceptor.resolveTenantId(null, null)).isNull();
	}

	@Test
	void whenThePartitionIsMissingTheTenantOfTheRequestIsUsed() {
		Task task = task("t1", "Practitioner/p1");

		interceptor.onCreated(task, request("farm-b"), null);

		verify(notify).sync(eq(task), eq("farm-b"), eq(null), any(TaskAssignment.class));
	}
}
