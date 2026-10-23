package org.farmsuite.fhir.consent;

import org.farmsuite.fhir.consent.CatalogProperties.TaskCapabilities;
import org.farmsuite.fhir.consent.ConsentMatcher.Access;
import org.farmsuite.fhir.consent.ExternalAssignmentPolicy.Requirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué tiene que cubrir el Consent para asignar una tarea a un externo. */
class ExternalAssignmentPolicyTest {

	@Test
	void anOrdinaryTask_onlyNeedsToReadThePatient() {
		assertThat(ExternalAssignmentPolicy.requirements(TaskCapabilities.NONE))
			.containsExactly(new Requirement("Patient", Access.READ));
	}

	@Test
	void aClinicalTask_needsWhatItReadsAndWrites_andWritingAlreadyIncludesReading() {
		TaskCapabilities visit = new TaskCapabilities(Set.of("Observation", "Condition", "Encounter"), Set.of("Condition", "Observation"));

		assertThat(ExternalAssignmentPolicy.requirements(visit)).containsExactly(
			new Requirement("Patient", Access.READ),
			new Requirement("Encounter", Access.READ),
			new Requirement("Condition", Access.WRITE),
			new Requirement("Observation", Access.WRITE));
	}

	@Test
	void thePatientIsNeverAskedTwice() {
		TaskCapabilities odd = new TaskCapabilities(Set.of("Patient", "Observation"), Set.of());

		assertThat(ExternalAssignmentPolicy.requirements(odd)).containsExactly(
			new Requirement("Patient", Access.READ), new Requirement("Observation", Access.READ));
	}

	@Test
	void missing_isWhatTheConsentDoesNotCover_inOrder() {
		List<Requirement> required = ExternalAssignmentPolicy.requirements(new TaskCapabilities(Set.of("Observation"), Set.of("Condition")));

		List<Requirement> missing = ExternalAssignmentPolicy.missing(required, requirement -> requirement.resourceType().equals("Patient"));

		assertThat(missing).extracting(Requirement::describe)
			.containsExactly("Observation (lectura)", "Condition (lectura y escritura)");
		assertThat(ExternalAssignmentPolicy.missing(required, requirement -> true)).isEmpty();
	}
}
