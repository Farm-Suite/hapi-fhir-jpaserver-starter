package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.annotation.PostConstruct;
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

	// custom-interceptor-classes (ver StarterJpaConfig#registerCustomInterceptors)
	// solo registra este interceptor en el IInterceptorService del RestfulServer,
	// que es lo que ServletRequestDetails#getInterceptorBroadcaster() expone para
	// peticiones HTTP reales (RestfulServer#newServletRequestDetails construye
	// `new ServletRequestDetails(getInterceptorService())`). Las llamadas internas
	// de sistema (p. ej. JpaPersistedResourceValidationSupport#fetchAllStructureDefinitions,
	// que arma un `new SystemRequestDetails()` sin RestfulServer detrás) resuelven
	// su broadcaster contra el bean `jpaInterceptorService` de la capa JPA en su
	// lugar (ver JpaConfig#jpaInterceptorService) — donde este interceptor nunca
	// llegaba a estar registrado. Sin este @PostConstruct, esas llamadas de sistema
	// no encuentran ningún hook para STORAGE_PARTITION_IDENTIFY_ANY y HAPI lanza
	// HAPI-1319 ("No interceptor provided a value").
	private final IInterceptorService jpaInterceptorService;

	@PostConstruct
	public void registerOnJpaInterceptorService() {
		jpaInterceptorService.registerInterceptor(this);
	}

	@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_ANY)
	public RequestPartitionId identify(RequestDetails theRequestDetails, ServletRequestDetails theServletRequestDetails) {
		String resourceName = theRequestDetails.getResourceName();

		// Llamadas internas de HAPI sin sesión de usuario ni tenant real detrás
		// (p. ej. JpaPersistedResourceValidationSupport#fetchAllStructureDefinitions,
		// invocada en cada $metadata para listar los profiles soportados vía
		// `new SystemRequestDetails()`). Al hacer CodeSystem/ConceptMap/
		// StructureDefinition/ValueSet particionables (ver RequestPartitionableResourcesHelper),
		// esta búsqueda de sistema dejó de resolverse sola a DEFAULT y necesita que
		// algún interceptor le dé una partición explícita — no hay tenant que
		// comprobar aquí, así que se busca en todas las particiones en vez de fallar
		// con HAPI-1319 ("No interceptor provided a value").
		if (theRequestDetails instanceof SystemRequestDetails && theRequestDetails.getTenantId() == null) {
			log.debug("System call without tenant for resource {} -> allPartitions", resourceName);
			return RequestPartitionId.allPartitions();
		}

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
