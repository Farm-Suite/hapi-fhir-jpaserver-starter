package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit test puro: cuándo se exige que {@code Task.for} sea un Patient existente en el tenant. */
@ExtendWith(MockitoExtension.class)
class ClinicalTaskValidationInterceptorTest {

	private static final RequestPartitionId FARM_A = RequestPartitionId.fromPartitionName("farm-a");

	@Mock
	private DaoRegistry daoRegistry;
	@Mock
	private IFhirResourceDao<Patient> patientDao;
	@Mock
	private RequestDetails requestDetails;

	private ClinicalTaskValidationInterceptor interceptor;

	@BeforeEach
	void setUp() {
		interceptor = new ClinicalTaskValidationInterceptor(daoRegistry);
	}

	@Test
	void operationalTask_withoutFor_isNotChecked() {
		interceptor.onCreated(new Task(), requestDetails, FARM_A);

		verify(daoRegistry, never()).getResourceDao(Patient.class);
	}

	@Test
	void forReferencingAnExistingPatient_passes() {
		when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
		when(patientDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(new Patient());

		assertThatCode(() -> interceptor.onCreated(taskFor("Patient/p1"), requestDetails, FARM_A)).doesNotThrowAnyException();
	}

	@Test
	void forReferencingAPatientThatDoesNotExistInTheTenant_isRejected() {
		when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
		when(patientDao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new ResourceNotFoundException("no"));

		assertThatThrownBy(() -> interceptor.onUpdated(new Task(), taskFor("Patient/p1"), requestDetails, FARM_A))
			.isInstanceOf(UnprocessableEntityException.class).hasMessageContaining("farm-a");
	}

	@Test
	void forReferencingADeletedPatient_isRejected() {
		when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
		when(patientDao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new ResourceGoneException("gone"));

		assertThatThrownBy(() -> interceptor.onCreated(taskFor("Patient/p1"), requestDetails, FARM_A))
			.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void forReferencingAnotherResourceType_isRejectedWithoutQueryingTheDatabase() {
		assertThatThrownBy(() -> interceptor.onCreated(taskFor("Practitioner/pr1"), requestDetails, FARM_A))
			.isInstanceOf(UnprocessableEntityException.class);
		verify(daoRegistry, never()).getResourceDao(Patient.class);
	}

	@Test
	void forWithoutId_isRejected() {
		assertThatThrownBy(() -> interceptor.onCreated(taskFor("Patient"), requestDetails, FARM_A))
			.isInstanceOf(UnprocessableEntityException.class);
	}

	private static Task taskFor(String reference) {
		Task task = new Task();
		task.setFor(new Reference(reference));
		return task;
	}
}
