package org.farmsuite.fhir.consent;

import ca.uhn.fhir.jpa.entity.PartitionEntity;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Las búsquedas particionadas necesitan el id numérico de la partición, no solo su nombre. */
class TenantRequestsTest {

	private static PartitionEntity partition(int id, String name) {
		PartitionEntity entity = new PartitionEntity();
		entity.setId(id);
		entity.setName(name);
		return entity;
	}

	@Test
	void systemIn_usesTheNumericIdOfThePartition() {
		IPartitionLookupSvc lookup = mock(IPartitionLookupSvc.class);
		when(lookup.getPartitionByName("tenant-a")).thenReturn(partition(42, "tenant-a"));

		SystemRequestDetails details = new TenantRequests(lookup).systemIn("tenant-a");

		assertThat(details.getRequestPartitionId().getFirstPartitionIdOrNull()).isEqualTo(42);
		assertThat(details.getRequestPartitionId().getFirstPartitionNameOrNull()).isEqualTo("tenant-a");
	}

	@Test
	void systemIn_resolvesEachTenantOnce() {
		IPartitionLookupSvc lookup = mock(IPartitionLookupSvc.class);
		when(lookup.getPartitionByName("t")).thenReturn(partition(7, "t"));
		TenantRequests requests = new TenantRequests(lookup);

		requests.systemIn("t");
		requests.systemIn("t");

		verify(lookup, times(1)).getPartitionByName("t");
	}
}
