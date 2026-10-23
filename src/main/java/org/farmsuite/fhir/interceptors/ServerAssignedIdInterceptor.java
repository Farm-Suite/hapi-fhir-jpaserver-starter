package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

/**
 * El id de un recurso lo asigna el servidor (UUID, {@code server_id_strategy}). {@code client_id_strategy: ANY}
 * deja crear un recurso con un {@code PUT} a un id elegido por el cliente, y dos recursos de tenants
 * distintos con el mismo id hacen ambiguas las referencias entre particiones: un Consent a
 * {@code Organization/x}, el destinatario de una tarea... Aquí se rechaza ese {@code PUT} para los usuarios.
 *
 * <p>La cuenta de servicio y las operaciones internas quedan exentas: siembran los perfiles y catálogos de
 * sistema ({@code Infra/execute/fhir}) con ids con significado, y Keycloak crea sus recursos. Actualizar un
 * recurso que ya existe no se ve afectado, ni crear con {@code POST} o con una actualización condicional
 * (sin id en la URL, el servidor asigna el suyo). Registrar en
 * {@code hapi.fhir.custom-interceptor-classes} (application.yaml y chart/values.yaml).
 */
@Interceptor
@Component
@RequiredArgsConstructor
public class ServerAssignedIdInterceptor {

	private final UserSessionRepository userSessionRepository;

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void onCreated(IBaseResource theResource, RequestDetails theRequestDetails) {
		if (theRequestDetails == null || theRequestDetails instanceof SystemRequestDetails || userSessionRepository.isAdmin()) return;
		if (theRequestDetails.getRestOperationType() != RestOperationTypeEnum.UPDATE) return;
		if (theRequestDetails.getId() == null || !theRequestDetails.getId().hasIdPart()) return;
		throw new InvalidRequestException(
			"El id de un " + theResource.fhirType() + " lo asigna el servidor: crea el recurso sin id (POST) en vez de elegir '"
				+ theRequestDetails.getId().getIdPart() + "'");
	}
}
