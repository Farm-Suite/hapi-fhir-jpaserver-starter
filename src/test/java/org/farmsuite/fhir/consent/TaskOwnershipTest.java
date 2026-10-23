package org.farmsuite.fhir.consent;

import org.farmsuite.fhir.consent.ConsentMatcher.Actor;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué tareas son de quien actúa: las asignadas a él, a un rol suyo o a la cola de su organización. */
class TaskOwnershipTest {

	private static final Actor DOCTOR = new Actor("dr-1", "pharmacy-ext");

	@Test
	void aTaskAssignedToThePractitioner_isTheirs_andOneAssignedToSomeoneElseIsNot() {
		assertThat(TaskOwnership.ownedBy(owned("Practitioner/dr-1"), DOCTOR, role -> null)).isTrue();
		assertThat(TaskOwnership.ownedBy(owned("Practitioner/dr-2"), DOCTOR, role -> null)).isFalse();
	}

	@Test
	void aTaskAssignedToARoleOfThePractitioner_isTheirs() {
		assertThat(TaskOwnership.ownedBy(owned("PractitionerRole/role-1"), DOCTOR, role -> "dr-1")).isTrue();
		assertThat(TaskOwnership.ownedBy(owned("PractitionerRole/role-1"), DOCTOR, role -> "dr-2")).isFalse();
		assertThat(TaskOwnership.ownedBy(owned("PractitionerRole/role-1"), DOCTOR, role -> null)).isFalse();
	}

	@Test
	void aTaskAssignedToTheirOrganization_isTheirs_butNotToAnotherOne() {
		assertThat(TaskOwnership.ownedBy(owned("Organization/pharmacy-ext"), DOCTOR, role -> null)).isTrue();
		assertThat(TaskOwnership.ownedBy(owned("Organization/other"), DOCTOR, role -> null)).isFalse();
		assertThat(TaskOwnership.ownedBy(owned("Organization/pharmacy-ext"), new Actor("dr-1", null), role -> null)).isFalse();
	}

	@Test
	void withoutOwner_orWithAnOwnerOfAnotherKind_nobodyOwnsIt() {
		assertThat(TaskOwnership.ownedBy(new Task(), DOCTOR, role -> "dr-1")).isFalse();
		assertThat(TaskOwnership.ownedBy(null, DOCTOR, role -> "dr-1")).isFalse();
		assertThat(TaskOwnership.ownedBy(owned("Patient/dr-1"), DOCTOR, role -> "dr-1")).isFalse();
		assertThat(TaskOwnership.ownedBy(owned("Practitioner/"), new Actor("", null), role -> null)).isFalse();
		assertThat(TaskOwnership.ownedBy(owned("Practitioner/dr-1"), new Actor(null, null), role -> null)).isFalse();
	}

	private static Task owned(String ownerReference) {
		Task task = new Task();
		task.setOwner(new Reference(ownerReference));
		return task;
	}
}
