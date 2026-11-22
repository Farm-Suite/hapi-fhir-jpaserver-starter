package org.farmsuite.fhir;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.r5.model.Practitioner;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.farmsuite.fhir.interceptors.PractitionerUsernameGuardInterceptor.PRACTITIONER_USERNAME_SYSTEM;

/**
 * El alta de un Practitioner con username (registro de profesionales desde Keycloak) llega como
 * {@code POST /fhir/DEFAULT/Practitioner}. {@code PractitionerUsernameGuardInterceptor} busca antes un
 * username repetido en la partición DEFAULT: esa búsqueda pasa por la resolución real de particiones de
 * HAPI, que un DAO simulado no cubre (Farm-Suite/Infra#11, HAPI-2223 con un nombre de partición sin id).
 */
class PractitionerRegistrationIT extends AbstractFarmSuiteServerIT {

	@Test
	void registeringAPractitionerWithANewUsername_createsItInDefault() {
		asServiceAccount("pr-admin");
		IGenericClient defaultClient = clientFor("DEFAULT");

		MethodOutcome outcome = defaultClient.create().resource(withUsername(uniqueUsername())).execute();

		assertThat(outcome.getCreated()).isTrue();
		assertThat(outcome.getId().getIdPart()).isNotBlank();
	}

	@Test
	void registeringASecondPractitionerWithTheSameUsername_isRejected() {
		asServiceAccount("pr-admin");
		IGenericClient defaultClient = clientFor("DEFAULT");
		String username = uniqueUsername();
		defaultClient.create().resource(withUsername(username)).execute();

		assertThatThrownBy(() -> defaultClient.create().resource(withUsername(username)).execute())
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining(username);
	}

	private static String uniqueUsername() {
		return "registro-" + UUID.randomUUID();
	}

	private static Practitioner withUsername(String username) {
		Practitioner practitioner = new Practitioner();
		practitioner.addName().setFamily("Registro");
		practitioner.addIdentifier().setSystem(PRACTITIONER_USERNAME_SYSTEM).setValue(username);
		return practitioner;
	}
}
