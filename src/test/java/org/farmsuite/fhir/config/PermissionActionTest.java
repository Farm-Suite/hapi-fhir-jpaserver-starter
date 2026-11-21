package org.farmsuite.fhir.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Traducción de las letras del claim {@code permissions} a acciones. */
class PermissionActionTest {

	@Test
	void translatesEachLetterToItsAction() {
		assertThat(PermissionAction.parse("r")).containsExactly(PermissionAction.READ);
		assertThat(PermissionAction.parse("s")).containsExactly(PermissionAction.SEARCH);
		assertThat(PermissionAction.parse("c")).containsExactly(PermissionAction.CREATE);
		assertThat(PermissionAction.parse("u")).containsExactly(PermissionAction.UPDATE);
		assertThat(PermissionAction.parse("d")).containsExactly(PermissionAction.DELETE);
		assertThat(PermissionAction.parse("p")).containsExactly(PermissionAction.PATCH);
	}

	@Test
	void combinesSeveralLettersAndIgnoresRepeats() {
		assertThat(PermissionAction.parse("rscud"))
			.containsExactlyInAnyOrder(PermissionAction.READ, PermissionAction.SEARCH, PermissionAction.CREATE,
				PermissionAction.UPDATE, PermissionAction.DELETE);
		assertThat(PermissionAction.parse("rr")).containsExactly(PermissionAction.READ);
	}

	@Test
	void noLettersMeansNoActions() {
		assertThat(PermissionAction.parse("")).isEmpty();
	}

	@Test
	void rejectsUnknownLetters() {
		assertThatThrownBy(() -> PermissionAction.parse("rx"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'x'");
	}

	@Test
	void lettersAreCaseSensitive() {
		assertThatThrownBy(() -> PermissionAction.parse("R")).isInstanceOf(IllegalArgumentException.class);
	}
}
