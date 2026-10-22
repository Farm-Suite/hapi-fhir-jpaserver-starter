package org.farmsuite.fhir.consent;

import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectKindTest {

	@Test
	void kindComesFromTheProfile_andDefaultsToPersonSoItIsProtectedByDefault() {
		assertThat(SubjectKind.of(new Patient())).isEqualTo(SubjectKind.PERSON);
		assertThat(SubjectKind.of(null)).isEqualTo(SubjectKind.PERSON);
		assertThat(SubjectKind.of(withProfile("https://farmsuite.org/fhir/DEFAULT/StructureDefinition/patient"))).isEqualTo(SubjectKind.PERSON);
		assertThat(SubjectKind.of(withProfile(SubjectKind.ANIMAL_PROFILE))).isEqualTo(SubjectKind.ANIMAL);
		assertThat(SubjectKind.of(withProfile(SubjectKind.PLANT_PROFILE))).isEqualTo(SubjectKind.PLANT);
	}

	@Test
	void anUnknownProfileDoesNotDowngradeAPersonToNonPerson() {
		assertThat(SubjectKind.of(withProfile("https://example.org/StructureDefinition/whatever"))).isEqualTo(SubjectKind.PERSON);
	}

	private static Patient withProfile(String url) {
		Patient patient = new Patient();
		patient.getMeta().addProfile(url);
		return patient;
	}
}
