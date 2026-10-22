package org.farmsuite.fhir.consent;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.partition.IPartitionLookupSvc;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Peticiones internas ({@link SystemRequestDetails}) sobre la partición de un tenant. Las lecturas
 * por id aceptan la partición solo por nombre, pero las <b>búsquedas</b> necesitan el id numérico
 * ({@code HAPI-2223: Partition IDs have not been set}), así que se resuelve una vez por tenant.
 */
@Component
@RequiredArgsConstructor
public class TenantRequests {

	private final IPartitionLookupSvc partitionLookupSvc;
	private final Map<String, RequestPartitionId> partitions = new ConcurrentHashMap<>();

	public SystemRequestDetails systemIn(String tenantId) {
		RequestPartitionId partition = partitions.computeIfAbsent(tenantId, tenant ->
			RequestPartitionId.fromPartitionIdAndName(partitionLookupSvc.getPartitionByName(tenant).getId(), tenant));
		return new SystemRequestDetails().setRequestPartitionId(partition);
	}
}
