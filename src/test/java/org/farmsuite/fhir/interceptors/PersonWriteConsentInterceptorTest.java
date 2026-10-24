package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.farmsuite.fhir.consent.ConsentMatcher.Access;
import org.farmsuite.fhir.consent.FarmSuiteConsentService;
import org.hl7.fhir.r5.model.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Escribir datos de una persona exige su consentimiento: el interceptor corta la escritura si el servicio dice que no. */
class PersonWriteConsentInterceptorTest {

	private FarmSuiteConsentService consents;
	private PersonWriteConsentInterceptor interceptor;
	private final RequestDetails request = new ServletRequestDetails();

	@BeforeEach
	void setUp() {
		consents = mock(FarmSuiteConsentService.class);
		interceptor = new PersonWriteConsentInterceptor(consents);
	}

	private static Observation observation(String id) {
		Observation observation = new Observation();
		observation.setId("Observation/" + id);
		return observation;
	}

	@Test
	void aCreationWithoutConsentIsForbiddenAndNamesTheResource() {
		Observation created = observation("o1");
		when(consents.mayCreate(request, created)).thenReturn(false);

		assertThatThrownBy(() -> interceptor.onCreated(created, request))
			.isInstanceOf(ForbiddenOperationException.class)
			.hasMessageContaining("Observation/o1")
			.hasMessageContaining("crear");
	}

	@Test
	void aCreationWithConsentGoesThrough() {
		Observation created = observation("o1");
		when(consents.mayCreate(request, created)).thenReturn(true);

		assertThatCode(() -> interceptor.onCreated(created, request)).doesNotThrowAnyException();
	}

	@Test
	void aMissingResourceOnCreationIsNotChecked() {
		assertThatCode(() -> interceptor.onCreated(null, request)).doesNotThrowAnyException();
		verifyNoInteractions(consents);
	}

	@Test
	void anUpdateChecksBothTheOldAndTheNewVersion_soThePatientCannotBeRemovedToDodgeTheCheck() {
		Observation before = observation("o1");
		Observation after = observation("o1");
		when(consents.mayUpdate(request, before, after)).thenReturn(false);

		assertThatThrownBy(() -> interceptor.onUpdated(before, after, request))
			.isInstanceOf(ForbiddenOperationException.class)
			.hasMessageContaining("modificar");
		verify(consents).mayUpdate(request, before, after);
	}

	@Test
	void anUpdateWithConsentGoesThrough() {
		Observation before = observation("o1");
		Observation after = observation("o1");
		when(consents.mayUpdate(request, before, after)).thenReturn(true);

		assertThatCode(() -> interceptor.onUpdated(before, after, request)).doesNotThrowAnyException();
	}

	@Test
	void anUpdateWithAMissingVersionIsNotChecked() {
		assertThatCode(() -> interceptor.onUpdated(null, observation("o1"), request)).doesNotThrowAnyException();
		assertThatCode(() -> interceptor.onUpdated(observation("o1"), null, request)).doesNotThrowAnyException();
		verifyNoInteractions(consents);
	}

	@Test
	void aDeletionWithoutConsentIsForbidden() {
		Observation deleted = observation("o1");
		when(consents.mayWrite(request, deleted, Access.DELETE)).thenReturn(false);

		assertThatThrownBy(() -> interceptor.onDeleted(deleted, request)).isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aDeletionWithConsentGoesThrough() {
		Observation deleted = observation("o1");
		when(consents.mayWrite(request, deleted, Access.DELETE)).thenReturn(true);

		assertThatCode(() -> interceptor.onDeleted(deleted, request)).doesNotThrowAnyException();
	}

	@Test
	void aMissingResourceOnDeletionIsNotChecked() {
		assertThatCode(() -> interceptor.onDeleted(null, request)).doesNotThrowAnyException();
		verify(consents, never()).mayWrite(any(), any(), any());
	}
}
