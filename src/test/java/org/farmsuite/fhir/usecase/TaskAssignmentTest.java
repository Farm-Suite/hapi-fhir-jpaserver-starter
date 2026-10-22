package org.farmsuite.fhir.usecase;

import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Las combinaciones de asignación de una Task y su organización creadora. */
class TaskAssignmentTest {

	private static final java.util.function.Function<String, PractitionerRole> NO_ROLES = id -> {
		throw new IllegalStateException("no debería leerse ningún PractitionerRole");
	};

	@Test
	void practitionerOnly() {
		assertThat(TaskAssignment.from(task("Practitioner/p1", null), NO_ROLES))
			.contains(new TaskAssignment("p1", null, null));
	}

	@Test
	void organizationOnly_isAQueue() {
		assertThat(TaskAssignment.from(task("Organization/o1", null), NO_ROLES))
			.contains(new TaskAssignment(null, "o1", null));
	}

	@Test
	void practitionerRole_givesBothPractitionerAndOrganization() {
		PractitionerRole role = new PractitionerRole();
		role.setPractitioner(new Reference("Practitioner/p1"));
		role.setOrganization(new Reference("Organization/o1"));

		assertThat(TaskAssignment.from(task("PractitionerRole/r1", null), id -> id.equals("r1") ? role : null))
			.contains(new TaskAssignment("p1", "o1", null));
	}

	@Test
	void practitionerRoleWithoutOrganization_isPractitionerOnly() {
		PractitionerRole role = new PractitionerRole();
		role.setPractitioner(new Reference("Practitioner/p1"));

		assertThat(TaskAssignment.from(task("PractitionerRole/r1", null), id -> role))
			.contains(new TaskAssignment("p1", null, null));
	}

	@Test
	void unreadableOrIncompletePractitionerRole_isNotMirrored() {
		assertThat(TaskAssignment.from(task("PractitionerRole/r1", null), id -> {
			throw new RuntimeException("not found");
		})).isEmpty();
		assertThat(TaskAssignment.from(task("PractitionerRole/r1", null), id -> new PractitionerRole())).isEmpty();
	}

	@Test
	void creatorOrganization_comesFromRequesterOnlyWhenItIsAnOrganization() {
		assertThat(TaskAssignment.from(task("Practitioner/p1", "Organization/c1"), NO_ROLES))
			.contains(new TaskAssignment("p1", null, "c1"));
		assertThat(TaskAssignment.from(task("Practitioner/p1", "Practitioner/author"), NO_ROLES))
			.contains(new TaskAssignment("p1", null, null));
	}

	@Test
	void noOwnerOrUnsupportedOwner_isNotMirrored() {
		assertThat(TaskAssignment.from(new Task(), NO_ROLES)).isEmpty();
		assertThat(TaskAssignment.from(task("Patient/x", null), NO_ROLES)).isEmpty();
		assertThat(TaskAssignment.from(task("Practitioner", null), NO_ROLES)).isEmpty();
	}

	private static Task task(String owner, String requester) {
		Task task = new Task();
		task.setOwner(new Reference(owner));
		if (requester != null) task.setRequester(new Reference(requester));
		return task;
	}
}
