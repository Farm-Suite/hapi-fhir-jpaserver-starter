package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.sso.session.UserSessionRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@Interceptor
@RequiredArgsConstructor
public class RequestDefaultTenantInterceptor {

	private static final Set<String> DEFAULT_TENANT_RESOURCES = Set.of(
		"Practitioner"
	);

	private final UserSessionRepository userSessionRepository;

	@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_ANY)
	public RequestPartitionId identify(RequestDetails theRequestDetails, ServletRequestDetails theServletRequestDetails) {
		String resourceName = theRequestDetails.getResourceName();

		RequestPartitionId resolvedPartition = null;
		if (DEFAULT_TENANT_RESOURCES.contains(resourceName)) {
			log.debug("Resource {} forced to DEFAULT partition", resourceName);
			resolvedPartition = RequestPartitionId.defaultPartition();
		} else {
			String tenantId = theRequestDetails.getTenantId();
			log.debug("Resource {} resolved to tenant {}", resourceName, tenantId);
			resolvedPartition = RequestPartitionId.fromPartitionName(tenantId);
		}

		log.debug("Resolved partition {}", resolvedPartition);
		enforceTenantAccess(resolvedPartition);
		return resolvedPartition;
	}

	private void enforceTenantAccess(RequestPartitionId partition) {
		String partitionName = partition.isDefaultPartition() ? null : partition.getFirstPartitionNameOrNull();

		boolean allowed = userSessionRepository.getUserSession()
			.map(session -> session.canAccessTenant(partitionName))
			.orElse(false);

		if (!allowed) {
			log.warn("Access denied to partition {} for current session", partitionName);
			throw new ForbiddenOperationException("No tiene acceso al tenant solicitado");
		}
	}
}
