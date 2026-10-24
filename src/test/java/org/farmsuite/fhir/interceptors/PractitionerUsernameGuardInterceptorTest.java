package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Practitioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.farmsuite.fhir.interceptors.PractitionerUsernameGuardInterceptor.PRACTITIONER_USERNAME_SYSTEM;

/** El username identifica a un practitioner en el login: es único y no se puede cambiar una vez asignado. */
class PractitionerUsernameGuardInterceptorTest {

	private IFhirResourceDao<Practitioner> dao;
	private PractitionerUsernameGuardInterceptor interceptor;
	private final RequestDetails request = new SystemRequestDetails();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		DaoRegistry registry = mock(DaoRegistry.class);
		dao = mock(IFhirResourceDao.class);
		when(registry.getResourceDao(Practitioner.class)).thenReturn(dao);
		interceptor = new PractitionerUsernameGuardInterceptor(registry);
	}

	private void existingPractitioners(int count) {
		IBundleProvider results = mock(IBundleProvider.class);
		when(results.getResources(anyInt(), anyInt())).thenReturn(count == 0 ? List.of() : List.of(new Practitioner()));
		when(dao.search(any(SearchParameterMap.class), any(RequestDetails.class))).thenReturn(results);
	}

	private static Practitioner withUsername(String username) {
		Practitioner practitioner = new Practitioner();
		practitioner.addIdentifier().setSystem(PRACTITIONER_USERNAME_SYSTEM).setValue(username);
		return practitioner;
	}

	@Test
	void aNewUsernameIsAccepted_andLookedUpInTheDefaultPartition() {
		existingPractitioners(0);

		assertThatCode(() -> interceptor.onCreated(withUsername("ana"), request)).doesNotThrowAnyException();

		ArgumentCaptor<RequestDetails> details = ArgumentCaptor.forClass(RequestDetails.class);
		verify(dao).search(any(SearchParameterMap.class), details.capture());
		assertThat(((SystemRequestDetails) details.getValue()).getRequestPartitionId().getPartitionNames()).containsExactly("DEFAULT");
	}

	@Test
	void aDuplicateUsernameIsRejected() {
		existingPractitioners(1);

		assertThatThrownBy(() -> interceptor.onCreated(withUsername("ana"), request))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("ana");
	}

	@Test
	void aPractitionerWithoutUsernameIsNotChecked() {
		assertThatCode(() -> interceptor.onCreated(new Practitioner(), request)).doesNotThrowAnyException();
		verify(dao, never()).search(any(SearchParameterMap.class), any(RequestDetails.class));
	}

	@Test
	void aBlankUsernameOrAnotherIdentifierSystemIsNotTreatedAsAUsername() {
		Practitioner blank = withUsername("  ");
		Practitioner other = new Practitioner();
		other.addIdentifier().setSystem("https://otro.example/id").setValue("ana");

		assertThatCode(() -> interceptor.onCreated(blank, request)).doesNotThrowAnyException();
		assertThatCode(() -> interceptor.onCreated(other, request)).doesNotThrowAnyException();
		verify(dao, never()).search(any(SearchParameterMap.class), any(RequestDetails.class));
	}

	@Test
	void otherResourceTypesAreIgnored() {
		assertThatCode(() -> interceptor.onCreated(new Observation(), request)).doesNotThrowAnyException();
		assertThatCode(() -> interceptor.onUpdated(new Observation(), new Observation(), request)).doesNotThrowAnyException();
	}

	@Test
	void anAssignedUsernameCannotBeChanged() {
		assertThatThrownBy(() -> interceptor.onUpdated(withUsername("ana"), withUsername("ana2"), request))
			.isInstanceOf(ForbiddenOperationException.class)
			.hasMessageContaining("no se puede modificar");
	}

	@Test
	void anAssignedUsernameCannotBeRemoved() {
		assertThatThrownBy(() -> interceptor.onUpdated(withUsername("ana"), new Practitioner(), request))
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void updatingOtherFieldsKeepingTheUsernameIsFine() {
		Practitioner after = withUsername("ana");
		after.addName().setFamily("Pérez");

		assertThatCode(() -> interceptor.onUpdated(withUsername("ana"), after, request)).doesNotThrowAnyException();
	}

	@Test
	void aPractitionerWithoutUsernameCanGetOne() {
		assertThatCode(() -> interceptor.onUpdated(new Practitioner(), withUsername("ana"), request)).doesNotThrowAnyException();
	}
}
