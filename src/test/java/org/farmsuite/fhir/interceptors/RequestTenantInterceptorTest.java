package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.model.ReadPartitionIdRequestDetails;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.farmsuite.fhir.consent.TenantAccessService;
import org.farmsuite.fhir.consent.TenantAccessService.Kind;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A qué partición FHIR va cada petición: la del tenant de la URL, DEFAULT para lo compartido, y nunca una ajena. */
class RequestTenantInterceptorTest {

	private TenantAccessService tenantAccess;
	private IInterceptorService jpaInterceptors;
	private RequestTenantInterceptor interceptor;

	@BeforeEach
	void setUp() {
		tenantAccess = mock(TenantAccessService.class);
		jpaInterceptors = mock(IInterceptorService.class);
		when(tenantAccess.resolve(any())).thenReturn(Kind.OWN_TENANT);
		interceptor = new RequestTenantInterceptor(tenantAccess, jpaInterceptors);
	}

	private static ServletRequestDetails request(String tenant, String resourceName) {
		ServletRequestDetails details = new ServletRequestDetails();
		details.setTenantId(tenant);
		details.setResourceName(resourceName);
		return details;
	}

	private RequestPartitionId create(String tenant, String resourceName) {
		return interceptor.identifyForCreate(new Patient(), request(tenant, resourceName), null);
	}

	private RequestPartitionId read(String tenant, String requestResource, String referencedResource) {
		ReadPartitionIdRequestDetails details = ReadPartitionIdRequestDetails.forSearchType(referencedResource, new SearchParameterMap(), null);
		return interceptor.identifyForRead(request(tenant, requestResource), null, details);
	}

	@Test
	void registersItselfOnTheJpaInterceptorServiceSoInternalCallsFindIt() {
		interceptor.registerOnJpaInterceptorService();

		verify(jpaInterceptors).registerInterceptor(interceptor);
	}

	@Test
	void aRealTenantInTheUrlIsThePartition() {
		RequestPartitionId partition = create("farm-a", "Patient");

		assertThat(partition.getPartitionNames()).containsExactly("farm-a");
		assertThat(partition.isDefaultPartition()).isFalse();
	}

	@Test
	void practitionersAlwaysLiveInDefault_whateverTheTenantInTheUrl() {
		assertThat(create("farm-a", "Practitioner").isDefaultPartition()).isTrue();
		assertThat(read("farm-a", "Practitioner", "Practitioner").isDefaultPartition()).isTrue();
	}

	@Test
	void theSystemCatalogIsReadFromDefault_theLiteralDefaultSegmentIsNotAPartitionName() {
		for (String tenant : new String[] {"DEFAULT", "default", "Default"}) {
			assertThat(create(tenant, "CodeSystem").isDefaultPartition()).as(tenant).isTrue();
		}
	}

	@Test
	void withoutATenantTheRequestGoesToDefault() {
		assertThat(create(null, "Patient").isDefaultPartition()).isTrue();
		assertThat(create("  ", "Patient").isDefaultPartition()).isTrue();
	}

	@Test
	void aSystemCallWithoutATenantSearchesEveryPartition() {
		SystemRequestDetails system = new SystemRequestDetails();
		system.setResourceName("StructureDefinition");

		RequestPartitionId partition = interceptor.identifyForCreate(null, system, null);

		assertThat(partition.isAllPartitions()).isTrue();
	}

	@Test
	void aSystemCallWithATenantStillResolvesThatTenant() {
		SystemRequestDetails system = new SystemRequestDetails();
		system.setTenantId("farm-a");
		system.setResourceName("Patient");

		assertThat(interceptor.identifyForCreate(null, system, null).getPartitionNames()).containsExactly("farm-a");
	}

	@Test
	void aTenantTheSessionCannotReachIsForbidden() {
		when(tenantAccess.resolve(any())).thenReturn(Kind.NONE);

		assertThatThrownBy(() -> create("farm-b", "Patient"))
			.isInstanceOf(ForbiddenOperationException.class)
			.hasMessageContaining("farm-b");
	}

	@Test
	void anExternalWithAConsentOrAnInternalCallMayEnterTheTenant() {
		when(tenantAccess.resolve(any())).thenReturn(Kind.BY_CONSENT);
		assertThat(create("farm-b", "Observation").getPartitionNames()).containsExactly("farm-b");

		when(tenantAccess.resolve(any())).thenReturn(Kind.NOT_SCOPED);
		assertThat(create("farm-b", "Observation").getPartitionNames()).containsExactly("farm-b");
	}

	@Test
	void theAccessCheckDoesNotApplyToDefaultNorToPractitioners() {
		when(tenantAccess.resolve(any())).thenReturn(Kind.NONE);

		assertThat(create("DEFAULT", "CodeSystem").isDefaultPartition()).isTrue();
		assertThat(create("farm-b", "Practitioner").isDefaultPartition()).isTrue();
	}

	@Test
	void whenReadingTheTypeBeingResolvedIsTheReferencedOneNotTheOneOfTheOuterRequest() {
		// Buscar PractitionerRole?practitioner=Practitioner/x obliga a HAPI a ubicar el Practitioner:
		// ese recurso vive en DEFAULT aunque la petición de fuera sea de un tenant.
		assertThat(read("farm-a", "PractitionerRole", "Practitioner").isDefaultPartition()).isTrue();
		assertThat(read("farm-a", "Practitioner", "Task").getPartitionNames()).containsExactly("farm-a");
	}

	@Test
	void whenReadingWithoutATypeTheOneOfTheRequestIsUsed() {
		ReadPartitionIdRequestDetails details = ReadPartitionIdRequestDetails.forServerOperation("$reindex");

		RequestPartitionId partition = interceptor.identifyForRead(request("farm-a", "Patient"), null, details);

		assertThat(partition.getPartitionNames()).containsExactly("farm-a");
	}
}
