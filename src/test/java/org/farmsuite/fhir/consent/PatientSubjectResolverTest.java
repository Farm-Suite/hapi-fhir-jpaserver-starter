package org.farmsuite.fhir.consent;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** De qué paciente es un recurso, qué es ese paciente y si una organización cuelga de otra. */
@SuppressWarnings("unchecked")
class PatientSubjectResolverTest {

	private static final String TENANT = "tenant-a";

	private IFhirResourceDao<Patient> patientDao;
	private IFhirResourceDao<Organization> organizationDao;
	private PatientSubjectResolver resolver;
	private RequestDetails request;

	@BeforeEach
	void setUp() {
		DaoRegistry registry = mock(DaoRegistry.class);
		patientDao = mock(IFhirResourceDao.class);
		organizationDao = mock(IFhirResourceDao.class);
		when(registry.getResourceDao(Patient.class)).thenReturn(patientDao);
		when(registry.getResourceDao(Organization.class)).thenReturn(organizationDao);
		TenantRequests tenants = mock(TenantRequests.class);
		when(tenants.systemIn(TENANT)).thenReturn(new SystemRequestDetails());
		resolver = new PatientSubjectResolver(registry, FhirContext.forR5Cached(), tenants);
		request = new SystemRequestDetails();
	}

	private void patientExists(String id, String managingOrg, String profile) {
		Patient patient = new Patient();
		patient.setId(id);
		if (managingOrg != null) patient.setManagingOrganization(new Reference("Organization/" + managingOrg));
		if (profile != null) patient.getMeta().addProfile(profile);
		when(patientDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(patient);
	}

	/** Organization/id existe y cuelga de partOf; se enruta por id porque el DAO es uno para todas. */
	private void organizations(String... idAndParentPairs) {
		when(organizationDao.read(any(IdType.class), any(RequestDetails.class))).thenAnswer(inv -> {
			String id = ((IdType) inv.getArgument(0)).getIdPart();
			for (int i = 0; i < idAndParentPairs.length; i += 2) {
				if (idAndParentPairs[i].equals(id)) {
					Organization org = new Organization();
					org.setId(id);
					if (idAndParentPairs[i + 1] != null) org.setPartOf(new Reference("Organization/" + idAndParentPairs[i + 1]));
					return org;
				}
			}
			throw new ResourceNotFoundException("Organization/" + id);
		});
	}

	// --- factsOf ---

	@Test
	void factsOf_aPatient_isItsOwnPatientWithoutReadingAnything() {
		Patient patient = new Patient();
		patient.setId("Patient/p1");
		patient.setManagingOrganization(new Reference("Organization/org-1"));

		Optional<PatientFacts> facts = resolver.factsOf(patient, TENANT, request);

		assertThat(facts).contains(new PatientFacts("p1", SubjectKind.PERSON, "org-1"));
		org.mockito.Mockito.verifyNoInteractions(patientDao);
	}

	@Test
	void factsOf_theKindComesFromTheProfile() {
		Patient animal = new Patient();
		animal.setId("Patient/a1");
		animal.getMeta().addProfile(SubjectKind.ANIMAL_PROFILE);

		assertThat(resolver.factsOf(animal, TENANT, request).orElseThrow().kind()).isEqualTo(SubjectKind.ANIMAL);
	}

	@Test
	void factsOf_aTaskThatReferencesAPatientByFor_readsThePatient() {
		patientExists("p1", "org-1", SubjectKind.PLANT_PROFILE);
		Task task = new Task();
		task.setFor(new Reference("Patient/p1"));

		Optional<PatientFacts> facts = resolver.factsOf(task, TENANT, request);

		assertThat(facts).contains(new PatientFacts("p1", SubjectKind.PLANT, "org-1"));
	}

	@Test
	void factsOf_anObservationThatReferencesAPatientBySubject_readsThePatient() {
		patientExists("p1", null, null);
		Observation observation = new Observation();
		observation.setSubject(new Reference("Patient/p1"));

		Optional<PatientFacts> facts = resolver.factsOf(observation, TENANT, request);

		assertThat(facts).contains(new PatientFacts("p1", SubjectKind.PERSON, null));
	}

	@Test
	void factsOf_aResourceWithoutPatient_isEmpty() {
		Observation noSubject = new Observation();
		Observation otherSubject = new Observation();
		otherSubject.setSubject(new Reference("Group/g1"));
		Task taskForAnOrganization = new Task();
		taskForAnOrganization.setFor(new Reference("Organization/o1"));

		assertThat(resolver.factsOf(noSubject, TENANT, request)).isEmpty();
		assertThat(resolver.factsOf(otherSubject, TENANT, request)).isEmpty();
		assertThat(resolver.factsOf(taskForAnOrganization, TENANT, request)).isEmpty();
		assertThat(resolver.factsOf(new Patient(), TENANT, request)).isEmpty(); // Patient sin id
		org.mockito.Mockito.verifyNoInteractions(patientDao);
	}

	@Test
	void factsOf_aPatientThatDoesNotExist_isEmpty() {
		when(patientDao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new ResourceNotFoundException("no"));
		Observation observation = new Observation();
		observation.setSubject(new Reference("Patient/ghost"));

		assertThat(resolver.factsOf(observation, TENANT, request)).isEmpty();
	}

	@Test
	void factsOf_aPatientThatCannotBeRead_isUnresolvedPerson_failClosed() {
		when(patientDao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new InternalErrorException("boom"));
		Observation observation = new Observation();
		observation.setSubject(new Reference("Patient/p1"));

		assertThat(resolver.factsOf(observation, TENANT, request)).contains(PatientFacts.unresolved("p1"));
	}

	@Test
	void factsOf_readsEachPatientOncePerRequest() {
		patientExists("p1", null, null);
		Observation observation = new Observation();
		observation.setSubject(new Reference("Patient/p1"));

		resolver.factsOf(observation, TENANT, request);
		resolver.factsOf(observation, TENANT, request);

		verify(patientDao, times(1)).read(any(IdType.class), any(RequestDetails.class));
	}

	// --- isWithin ---

	@Test
	void isWithin_theSameOrganization_isTrueWithoutReading() {
		assertThat(resolver.isWithin(TENANT, "org-1", "org-1", request)).isTrue();
		org.mockito.Mockito.verifyNoInteractions(organizationDao);
	}

	@Test
	void isWithin_walksPartOfUpwards() {
		organizations("leaf", "mid", "mid", "root", "root", null);

		assertThat(resolver.isWithin(TENANT, "leaf", "root", request)).isTrue();
		assertThat(resolver.isWithin(TENANT, "leaf", "mid", request)).isTrue();
	}

	@Test
	void isWithin_anUnrelatedAncestor_isFalse() {
		organizations("leaf", "mid", "mid", "root", "root", null);

		assertThat(resolver.isWithin(TENANT, "leaf", "elsewhere", request)).isFalse();
		assertThat(resolver.isWithin(TENANT, "root", "leaf", request)).isFalse(); // hacia abajo no
	}

	@Test
	void isWithin_aCycle_terminates() {
		organizations("a", "b", "b", "a");

		assertThat(resolver.isWithin(TENANT, "a", "elsewhere", request)).isFalse();
	}

	@Test
	void isWithin_aMissingOrganization_terminatesWithFalse() {
		organizations("leaf", "ghost");

		assertThat(resolver.isWithin(TENANT, "leaf", "root", request)).isFalse();
	}

	@Test
	void isWithin_aNullOrganization_isFalse() {
		assertThat(resolver.isWithin(TENANT, null, "root", request)).isFalse();
	}
}
