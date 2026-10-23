package org.farmsuite.fhir;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Observation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El id de un recurso lo asigna el servidor (UUID): un usuario no puede crear un recurso eligiendo su id
 * con un {@code PUT}. Dos recursos de tenants distintos con el mismo id harían ambiguas las referencias
 * entre particiones (un Consent a {@code Organization/x}, el destinatario de una tarea...). La cuenta de
 * servicio queda exenta: siembra los perfiles y catálogos de sistema con ids con significado.
 */
class ServerAssignedIdIT extends AbstractFarmSuiteServerIT {

	private static final List<String> EVERYTHING = List.of("*.rscud");

	@Test
	void aUserCreatingWithPost_getsAUuidFromTheServer() {
		asHuman("pr-7", "org-1", EVERYTHING);

		String id = tenantClient.create().resource(observation()).execute().getId().getIdPart();

		assertThat(UUID.fromString(id)).isNotNull();
	}

	@Test
	void aUserCreatingWithPutAnIdOfTheirOwn_isRejected() {
		asHuman("pr-7", "org-1", EVERYTHING);
		Observation chosen = observation();
		chosen.setId("my-own-id");

		assertThatThrownBy(() -> tenantClient.update().resource(chosen).execute()).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aUserUpdatingWithPutAResourceThatExists_isStillFine() {
		asHuman("pr-7", "org-1", EVERYTHING);
		String id = tenantClient.create().resource(observation()).execute().getId().getIdPart();
		Observation stored = tenantClient.read().resource(Observation.class).withId(id).execute();
		stored.getCode().setText("Peso corregido");

		assertThatCode(() -> tenantClient.update().resource(stored).execute()).doesNotThrowAnyException();
	}

	@Test
	void theServiceAccount_mayStillChooseIds_forTheSystemCatalogsAndProfiles() {
		asServiceAccount("pr-admin");
		Observation seeded = observation();
		seeded.setId("seeded-with-a-meaningful-id");

		assertThatCode(() -> tenantClient.update().resource(seeded).execute()).doesNotThrowAnyException();
	}

	private static Observation observation() {
		Observation observation = new Observation();
		observation.setStatus(Enumerations.ObservationStatus.FINAL);
		observation.getCode().setText("Peso");
		return observation;
	}
}
