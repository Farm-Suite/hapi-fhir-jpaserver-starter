package org.farmsuite.fhir.consent;

import org.farmsuite.fhir.consent.CatalogProperties.TaskCapabilities;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.CodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué dicen los catálogos de sistema sobre lo que puede hacer un externo según el tipo de tarea y de organización. */
class CatalogPropertiesTest {

	@Test
	void aTaskTypeLists_whatItReadsAndWrites_andWhatItWritesItAlsoReads() {
		CodeSystem types = new CodeSystem();
		CodeSystem.ConceptDefinitionComponent visit = types.addConcept().setCode("clinical-visit");
		visit.addProperty().setCode("consent-read").setValue(new CodeType("Observation"));
		visit.addProperty().setCode("consent-read").setValue(new CodeType("Encounter"));
		visit.addProperty().setCode("consent-write").setValue(new CodeType("Condition"));

		TaskCapabilities capabilities = CatalogProperties.taskCapabilities(types).get("clinical-visit");

		assertThat(capabilities.write()).containsExactly("Condition");
		assertThat(capabilities.read()).containsExactlyInAnyOrder("Observation", "Encounter", "Condition");
	}

	@Test
	void aTaskTypeWithoutProperties_grantsNothingBeyondTheTask() {
		CodeSystem types = new CodeSystem();
		types.addConcept().setCode("feeding");

		Map<String, TaskCapabilities> byCode = CatalogProperties.taskCapabilities(types);

		assertThat(byCode.get("feeding")).isEqualTo(new TaskCapabilities(Set.of(), Set.of()));
		assertThat(byCode.get("unknown")).isNull();
		assertThat(CatalogProperties.taskCapabilities(null)).isEmpty();
	}

	@Test
	void propertiesWithAnotherCodeOrAnotherValueTypeAreIgnored() {
		CodeSystem types = new CodeSystem();
		CodeSystem.ConceptDefinitionComponent concept = types.addConcept().setCode("treatment");
		concept.addProperty().setCode("something-else").setValue(new CodeType("Observation"));
		concept.addProperty().setCode("consent-write").setValue(new BooleanType(true));

		assertThat(CatalogProperties.taskCapabilities(types).get("treatment")).isEqualTo(TaskCapabilities.NONE);
	}

	@Test
	void nestedConceptsCountToo() {
		CodeSystem types = new CodeSystem();
		CodeSystem.ConceptDefinitionComponent parent = types.addConcept().setCode("clinical");
		parent.addConcept().setCode("surgery").addProperty().setCode("consent-write").setValue(new CodeType("Procedure"));

		assertThat(CatalogProperties.taskCapabilities(types).get("surgery").write()).containsExactly("Procedure");
	}

	@Test
	void onlyTheOrganizationTypesMarkedAsExternalServiceAreListed() {
		CodeSystem types = new CodeSystem();
		types.addConcept().setCode("farm");
		types.addConcept().setCode("pharmacy").addProperty().setCode("external-service").setValue(new BooleanType(true));
		types.addConcept().setCode("cooperative").addProperty().setCode("external-service").setValue(new BooleanType(false));

		assertThat(CatalogProperties.externalServiceOrganizationTypes(types)).containsExactly("pharmacy");
		assertThat(CatalogProperties.externalServiceOrganizationTypes(null)).isEmpty();
	}
}
