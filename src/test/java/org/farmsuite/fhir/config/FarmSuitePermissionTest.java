package org.farmsuite.fhir.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Formato de los códigos del claim {@code permissions}: {@code Tipo.acciones}, p. ej. {@code Patient.rscud}. */
class FarmSuitePermissionTest {

	@Test
	void parsesAResourceTypeWithItsActions() {
		FarmSuitePermission permission = FarmSuitePermission.parse("Patient.rs");

		assertThat(permission.resourceType()).isEqualTo("Patient");
		assertThat(permission.actions()).containsExactlyInAnyOrder(PermissionAction.READ, PermissionAction.SEARCH);
		assertThat(permission.isWildcard()).isFalse();
	}

	@Test
	void parsesEveryAction() {
		assertThat(FarmSuitePermission.parse("Task.rscudp").actions()).isEqualTo(Set.of(PermissionAction.values()));
	}

	@Test
	void theWildcardAppliesToAllResourceTypes() {
		FarmSuitePermission permission = FarmSuitePermission.parse("*.rscud");

		assertThat(permission.isWildcard()).isTrue();
		assertThat(permission.actions()).hasSize(5);
	}

	@Test
	void theOrderAndRepetitionOfActionsDoNotMatter() {
		assertThat(FarmSuitePermission.parse("Task.dcr").actions()).isEqualTo(FarmSuitePermission.parse("Task.rcdd").actions());
	}

	@Test
	void anUnknownActionIsRejected() {
		assertThatThrownBy(() -> FarmSuitePermission.parse("Patient.rx"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'x'");
	}

	@Test
	void aMalformedPermissionIsRejected() {
		for (String raw : new String[] {"Patient", "Patient.", ".rs", "Patient.RS", "Pat ient.r", "Patient.r.s", "", "CONF_READ", "Patient.rs "}) {
			assertThatThrownBy(() -> FarmSuitePermission.parse(raw))
				.as("'%s' no es un permiso válido", raw)
				.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
