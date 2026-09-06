package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.ReadPartitionIdRequestDetails;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@Interceptor
@RequiredArgsConstructor
public class RequestTenantInterceptor {

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

	// Antes un único método hookeado en STORAGE_PARTITION_IDENTIFY_ANY. Ese pointcut es un
	// catch-all: por diseño de HAPI nunca recibe ReadPartitionIdRequestDetails, así que cuando el
	// motor de búsqueda resuelve internamente a qué partición pertenece un id REFERENCIADO (p. ej.
	// al evaluar PractitionerRole?practitioner=Practitioner/{id}, HAPI necesita saber en qué
	// partición vive ese Practitioner) el hook solo veía el resourceType de la request de AFUERA
	// ("PractitionerRole"), nunca el del recurso referenciado ("Practitioner") — por eso esa
	// búsqueda cross-partition nunca encontraba nada aunque el dato estuviera bien guardado.
	// Partido en los dos pointcuts operacionales reales de HAPI (_CREATE y _READ — este último
	// cubre todo lo que no es un create: read/vread, search, history, $reindex, y la resolución
	// interna de arriba) para que _READ SÍ reciba ReadPartitionIdRequestDetails.getResourceType(),
	// que en ese caso interno correctamente dice "Practitioner".
	@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_CREATE)
	public RequestPartitionId identifyForCreate(
			IBaseResource theResource, RequestDetails theRequestDetails, ServletRequestDetails theServletRequestDetails) {
		return resolvePartition(theRequestDetails, theRequestDetails.getResourceName());
	}

	@Hook(Pointcut.STORAGE_PARTITION_IDENTIFY_READ)
	public RequestPartitionId identifyForRead(
			RequestDetails theRequestDetails,
			ServletRequestDetails theServletRequestDetails,
			ReadPartitionIdRequestDetails theDetails) {
		String resourceName = theDetails.getResourceType() != null
				? theDetails.getResourceType()
				: theRequestDetails.getResourceName();
		return resolvePartition(theRequestDetails, resourceName);
	}

	private RequestPartitionId resolvePartition(RequestDetails theRequestDetails, String resourceName) {
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

		String tenantId = theRequestDetails.getTenantId();

		RequestPartitionId resolvedPartition;
		if (DEFAULT_TENANT_RESOURCES.contains(resourceName)) {
			log.debug("Resource {} forced to DEFAULT partition", resourceName);
			return RequestPartitionId.defaultPartition();
		} else {
			log.debug("Resource {} resolved to tenant {}", resourceName, tenantId);
			resolvedPartition = RequestPartitionId.fromPartitionName(tenantId);
		}

		log.debug("Resolved partition {}", resolvedPartition);

		return resolvedPartition;
	}
}
