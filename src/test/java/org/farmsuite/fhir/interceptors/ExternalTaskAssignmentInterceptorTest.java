package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.farmsuite.fhir.consent.CatalogProperties;
import org.farmsuite.fhir.consent.CatalogProperties.TaskCapabilities;
import org.farmsuite.fhir.consent.ConsentFinder;
import org.farmsuite.fhir.consent.ConsentMatcher.Access;
import org.farmsuite.fhir.consent.ConsentMatcher.Actor;
import org.farmsuite.fhir.consent.SystemCatalogs;
import org.farmsuite.fhir.consent.TenantRequests;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Asignar una tarea a un externo exige el Consent que pide su tipo; a los del tenant no se les pide nada. */
class ExternalTaskAssignmentInterceptorTest {

	private static final String TENANT = "farm-a";
	private static final String TYPE = "visit";

	private DaoRegistry daoRegistry;
	private IFhirResourceDao<PractitionerRole> roleDao;
	private IFhirResourceDao<Organization> organizationDao;
	private TenantRequests tenantRequests;
	private ConsentFinder consentFinder;
	private SystemCatalogs catalogs;
	private ExternalTaskAssignmentInterceptor interceptor;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		daoRegistry = mock(DaoRegistry.class);
		roleDao = mock(IFhirResourceDao.class);
		organizationDao = mock(IFhirResourceDao.class);
		when(daoRegistry.getResourceDao(PractitionerRole.class)).thenReturn(roleDao);
		when(daoRegistry.getResourceDao(Organization.class)).thenReturn(organizationDao);
		tenantRequests = mock(TenantRequests.class);
		when(tenantRequests.systemIn(TENANT)).thenReturn(new SystemRequestDetails().setRequestPartitionId(RequestPartitionId.fromPartitionName(TENANT)));
		consentFinder = mock(ConsentFinder.class);
		catalogs = mock(SystemCatalogs.class);
		// El tipo "visit" pide leer y escribir Observation.
		when(catalogs.taskCapabilities(TYPE)).thenReturn(new TaskCapabilities(Set.of("Observation"), Set.of("Observation")));
		interceptor = new ExternalTaskAssignmentInterceptor(daoRegistry, tenantRequests, consentFinder, catalogs);
	}

	// --- helpers ---

	private static RequestDetails request(String tenant) {
		ServletRequestDetails details = new ServletRequestDetails();
		details.setTenantId(tenant);
		return details;
	}

	private static Task task(String owner, String patient, String type) {
		Task task = new Task();
		if (owner != null) task.getOwner().setReference(owner);
		if (patient != null) task.getFor().setReference(patient);
		if (type != null) task.getCode().addCoding().setSystem(CatalogProperties.TASK_TYPE_SYSTEM).setCode(type);
		return task;
	}

	private void practitionerHasRoleInTenant(boolean hasRole) {
		IBundleProvider roles = mock(IBundleProvider.class);
		when(roles.getResources(0, 1)).thenReturn(hasRole ? List.of(new PractitionerRole()) : List.of());
		when(roleDao.search(any(SearchParameterMap.class), any(RequestDetails.class))).thenReturn(roles);
	}

	private void organizationInTenant() {
		when(organizationDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(new Organization());
	}

	/** Organization externa: el tenant no la ve, pero desde todas las particiones se lee con los tipos dados. */
	private void externalOrganization(String... typeCodes) {
		Organization org = new Organization();
		for (String code : typeCodes) org.addType().addCoding().setCode(code);
		when(organizationDao.read(any(IdType.class), any(RequestDetails.class))).thenAnswer(call -> {
			SystemRequestDetails details = call.getArgument(1);
			if (details.getRequestPartitionId() != null && details.getRequestPartitionId().isAllPartitions()) return org;
			throw new ResourceNotFoundException("no");
		});
	}

	private void consentCovers(boolean covers) {
		when(consentFinder.permits(anyString(), anyString(), any(Actor.class), anyString(), any(Access.class), any(Instant.class)))
			.thenReturn(covers);
	}

	// --- internos: no piden Consent ---

	@Test
	void anInternalPractitionerWithARoleInTheTenantNeedsNoConsent() {
		practitionerHasRoleInTenant(true);

		assertThatCode(() -> interceptor.onCreated(task("Practitioner/p1", "Patient/pt1", TYPE), request(TENANT)))
			.doesNotThrowAnyException();

		verifyNoInteractions(consentFinder);
	}

	@Test
	void aPractitionerRoleOwnerNeedsNoConsent() {
		assertThatCode(() -> interceptor.onCreated(task("PractitionerRole/r1", "Patient/pt1", TYPE), request(TENANT)))
			.doesNotThrowAnyException();

		verifyNoInteractions(consentFinder, roleDao);
	}

	@Test
	void anOrganizationOfTheTenantNeedsNoConsent() {
		organizationInTenant();

		assertThatCode(() -> interceptor.onCreated(task("Organization/o1", "Patient/pt1", TYPE), request(TENANT)))
			.doesNotThrowAnyException();

		verifyNoInteractions(consentFinder);
	}

	@Test
	void aTaskWithoutOwnerIsNotChecked() {
		assertThatCode(() -> interceptor.onCreated(task(null, "Patient/pt1", TYPE), request(TENANT)))
			.doesNotThrowAnyException();

		verifyNoInteractions(consentFinder, roleDao, organizationDao);
	}

	@Test
	void serverCallsAndRequestsWithoutARealTenantAreExempt() {
		Task external = task("Practitioner/p1", "Patient/pt1", TYPE);

		interceptor.onCreated(external, new SystemRequestDetails());
		interceptor.onCreated(external, null);
		interceptor.onCreated(external, request(null));
		interceptor.onCreated(external, request(" "));
		interceptor.onCreated(external, request("DEFAULT"));

		verifyNoInteractions(consentFinder, roleDao, organizationDao);
	}

	@Test
	void aNonTaskResourceIsIgnored() {
		interceptor.onCreated(new Organization(), request(TENANT));
		interceptor.onUpdated(new Organization(), new Organization(), request(TENANT));

		verifyNoInteractions(consentFinder, roleDao, organizationDao);
	}

	// --- externo: Practitioner ---

	@Test
	void anExternalPractitionerWithoutConsentGets422() {
		practitionerHasRoleInTenant(false);
		consentCovers(false);

		assertThatThrownBy(() -> interceptor.onCreated(task("Practitioner/p1", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("Practitioner/p1")
			.hasMessageContaining("Patient/pt1")
			.hasMessageContaining("Observation (lectura y escritura)");
	}

	@Test
	void anExternalPractitionerWithTheConsentPasses() {
		practitionerHasRoleInTenant(false);
		consentCovers(true);

		assertThatCode(() -> interceptor.onCreated(task("Practitioner/p1", "Patient/pt1", TYPE), request(TENANT)))
			.doesNotThrowAnyException();

		verify(consentFinder).permits(eq(TENANT), eq("pt1"), eq(new Actor("p1", null)), eq("Patient"), eq(Access.READ), any(Instant.class));
		verify(consentFinder).permits(eq(TENANT), eq("pt1"), eq(new Actor("p1", null)), eq("Observation"), eq(Access.WRITE), any(Instant.class));
	}

	@Test
	void theMessageListsOnlyWhatTheConsentDoesNotCover() {
		practitionerHasRoleInTenant(false);
		// cubre la ficha del paciente, pero no las Observation
		when(consentFinder.permits(anyString(), anyString(), any(Actor.class), anyString(), any(Access.class), any(Instant.class)))
			.thenAnswer(call -> "Patient".equals(call.getArgument(3)));

		assertThatThrownBy(() -> interceptor.onCreated(task("Practitioner/p1", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("Observation (lectura y escritura)")
			.hasMessageNotContaining("Patient (lectura)");
	}

	@Test
	void ifTheRoleLookupFailsThePractitionerIsTreatedAsExternal() {
		when(roleDao.search(any(SearchParameterMap.class), any(RequestDetails.class))).thenThrow(new RuntimeException("db down"));
		consentCovers(false);

		assertThatThrownBy(() -> interceptor.onCreated(task("Practitioner/p1", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class);
	}

	// --- externo: Organization ---

	@Test
	void anExternalOrganizationOfAnExternalServiceTypeWithConsentPasses() {
		externalOrganization("pharmacy");
		when(catalogs.isExternalServiceOrganizationType("pharmacy")).thenReturn(true);
		consentCovers(true);

		assertThatCode(() -> interceptor.onCreated(task("Organization/o9", "Patient/pt1", TYPE), request(TENANT)))
			.doesNotThrowAnyException();

		verify(consentFinder).permits(eq(TENANT), eq("pt1"), eq(new Actor(null, "o9")), eq("Patient"), eq(Access.READ), any(Instant.class));
	}

	@Test
	void anExternalOrganizationOfAnotherTypeGets422WithoutLookingForConsent() {
		externalOrganization("hospital");
		when(catalogs.isExternalServiceOrganizationType("hospital")).thenReturn(false);

		assertThatThrownBy(() -> interceptor.onCreated(task("Organization/o9", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("Organization/o9")
			.hasMessageContaining("servicios externos");

		verifyNoInteractions(consentFinder);
	}

	@Test
	void anExternalOrganizationWithoutTypeGets422() {
		externalOrganization();

		assertThatThrownBy(() -> interceptor.onCreated(task("Organization/o9", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void anOrganizationThatDoesNotExistAnywhereGets422() {
		when(organizationDao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new ResourceNotFoundException("no"));

		assertThatThrownBy(() -> interceptor.onCreated(task("Organization/o9", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("no existe");
	}

	@Test
	void ifTheTenantLookupFailsTheOrganizationIsTreatedAsExternalAndMustBeAnExternalService() {
		when(organizationDao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new RuntimeException("db down"));

		assertThatThrownBy(() -> interceptor.onCreated(task("Organization/o9", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class);
	}

	// --- sin paciente / sin tipo ---

	@Test
	void withoutPatientAnActiveGrantOnTheTenantIsEnough() {
		practitionerHasRoleInTenant(false);
		when(consentFinder.hasActiveGrant(eq(TENANT), eq(new Actor("p1", null)), any(Instant.class))).thenReturn(true);

		assertThatCode(() -> interceptor.onCreated(task("Practitioner/p1", null, TYPE), request(TENANT)))
			.doesNotThrowAnyException();

		verify(consentFinder, never()).permits(anyString(), anyString(), any(), anyString(), any(), any());
	}

	@Test
	void withoutPatientAndWithoutActiveGrantGets422() {
		practitionerHasRoleInTenant(false);
		when(consentFinder.hasActiveGrant(anyString(), any(Actor.class), any(Instant.class))).thenReturn(false);

		assertThatThrownBy(() -> interceptor.onCreated(task("Practitioner/p1", null, TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("consentimiento vigente");
	}

	@Test
	void aForThatIsNotAPatientCountsAsNoPatient() {
		practitionerHasRoleInTenant(false);
		when(consentFinder.hasActiveGrant(anyString(), any(Actor.class), any(Instant.class))).thenReturn(false);

		assertThatThrownBy(() -> interceptor.onCreated(task("Practitioner/p1", "Group/g1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("consentimiento vigente");
	}

	@Test
	void withoutTaskTypeOnlyThePatientRecordIsRequired() {
		practitionerHasRoleInTenant(false);
		when(catalogs.taskCapabilities(null)).thenReturn(TaskCapabilities.NONE);
		consentCovers(true);

		assertThatCode(() -> interceptor.onCreated(task("Practitioner/p1", "Patient/pt1", null), request(TENANT)))
			.doesNotThrowAnyException();

		verify(consentFinder).permits(eq(TENANT), eq("pt1"), any(Actor.class), eq("Patient"), eq(Access.READ), any(Instant.class));
		verify(consentFinder, never()).permits(anyString(), anyString(), any(), eq("Observation"), any(), any());
	}

	@Test
	void anUnknownTaskTypeAsksForNothingBeyondThePatientRecord() {
		practitionerHasRoleInTenant(false);
		when(catalogs.taskCapabilities("other")).thenReturn(TaskCapabilities.NONE);
		consentCovers(true);

		assertThatCode(() -> interceptor.onCreated(task("Practitioner/p1", "Patient/pt1", "other"), request(TENANT)))
			.doesNotThrowAnyException();

		verify(consentFinder, never()).permits(anyString(), anyString(), any(), eq("Observation"), any(), any());
	}

	// --- update: relevantChange ---

	@Test
	void anUpdateWithoutRelevantChangeChecksNothing() {
		Task old = task("Practitioner/p1", "Patient/pt1", TYPE);
		Task changed = task("Practitioner/p1", "Patient/pt1", TYPE);
		changed.setDescription("otro texto");

		interceptor.onUpdated(old, changed, request(TENANT));

		verifyNoInteractions(consentFinder, roleDao, organizationDao);
	}

	@Test
	void changingTheOwnerChecksTheNewOne() {
		practitionerHasRoleInTenant(false);
		consentCovers(false);

		assertThatThrownBy(() -> interceptor.onUpdated(
			task("Practitioner/p1", "Patient/pt1", TYPE), task("Practitioner/p2", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("Practitioner/p2");
	}

	@Test
	void changingThePatientChecksConsentForTheNewPatient() {
		practitionerHasRoleInTenant(false);
		consentCovers(false);

		assertThatThrownBy(() -> interceptor.onUpdated(
			task("Practitioner/p1", "Patient/pt1", TYPE), task("Practitioner/p1", "Patient/pt2", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class)
			.hasMessageContaining("Patient/pt2");
	}

	@Test
	void changingTheTaskTypeIsAlsoChecked() {
		practitionerHasRoleInTenant(false);
		consentCovers(false);

		assertThatThrownBy(() -> interceptor.onUpdated(
			task("Practitioner/p1", "Patient/pt1", "other"), task("Practitioner/p1", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class);
	}

	@Test
	void anUpdateWhoseOldVersionIsNotATaskIsCheckedLikeACreation() {
		practitionerHasRoleInTenant(false);
		consentCovers(false);

		assertThatThrownBy(() -> interceptor.onUpdated(new Organization(), task("Practitioner/p1", "Patient/pt1", TYPE), request(TENANT)))
			.isInstanceOf(UnprocessableEntityException.class);
	}
}
