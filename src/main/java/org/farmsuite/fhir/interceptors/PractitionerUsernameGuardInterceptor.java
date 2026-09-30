package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Practitioner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Invariantes del identifier "username" de un Practitioner (system
 * {@link #PRACTITIONER_USERNAME_SYSTEM}), independientes de cualquier plantilla de rol —
 * mismo criterio que {@code FarmSuiteRules#applyPractitionerSelfServiceRules}: reglas
 * estructurales, no de permisos.
 *
 * <ul>
 *   <li>Único en todo el servidor: no puede haber dos Practitioner con el mismo username.</li>
 *   <li>Inmutable una vez asignado: ni el propio dueño (vía el self-service de
 *       {@code FarmSuiteRules}) ni nadie más puede cambiarlo después de creado.</li>
 * </ul>
 *
 * El formulario de "Mi perfil" en {@code suite-front} ya deshabilita este campo en la UI
 * (extensión {@code element-readonly-once-set} en {@code StructureDefinition-practitioner}),
 * pero eso es solo una barrera de interfaz — cualquier cliente que hable directo con la API
 * FHIR podía saltarla. Esta es la validación real, del lado del servidor.
 */
@Interceptor
@Component
@RequiredArgsConstructor
public class PractitionerUsernameGuardInterceptor {

	public static final String PRACTITIONER_USERNAME_SYSTEM = "https://farmsuite.org/extensions/practitioner-username";

	/** Practitioner siempre vive en la partición DEFAULT (ver RequestTenantInterceptor). */
	private static final String DEFAULT_PARTITION = "DEFAULT";

	private final DaoRegistry daoRegistry;

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void onCreated(IBaseResource theResource, RequestDetails theRequestDetails) {
		if (!(theResource instanceof Practitioner practitioner)) return;

		usernameOf(practitioner).ifPresent(username -> {
			if (usernameAlreadyExists(username)) {
				throw new UnprocessableEntityException(
					"Ya existe un practitioner con username '" + username + "'");
			}
		});
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void onUpdated(IBaseResource theOldResource, IBaseResource theNewResource, RequestDetails theRequestDetails) {
		if (!(theOldResource instanceof Practitioner oldPractitioner)) return;
		if (!(theNewResource instanceof Practitioner newPractitioner)) return;

		Optional<String> oldUsername = usernameOf(oldPractitioner);
		if (oldUsername.isEmpty()) return; // todavía sin username asignado: nada que proteger

		Optional<String> newUsername = usernameOf(newPractitioner);
		if (!oldUsername.equals(newUsername)) {
			throw new ForbiddenOperationException(
				"El username de un practitioner no se puede modificar una vez asignado");
		}
	}

	private Optional<String> usernameOf(Practitioner practitioner) {
		return practitioner.getIdentifier().stream()
			.filter(Identifier::hasSystem)
			.filter(identifier -> PRACTITIONER_USERNAME_SYSTEM.equals(identifier.getSystem()))
			.map(Identifier::getValue)
			.filter(value -> value != null && !value.isBlank())
			.findFirst();
	}

	private boolean usernameAlreadyExists(String username) {
		IFhirResourceDao<Practitioner> dao = daoRegistry.getResourceDao(Practitioner.class);

		SearchParameterMap map = new SearchParameterMap();
		map.add("username", new TokenParam(PRACTITIONER_USERNAME_SYSTEM, username));
		map.setLoadSynchronousUpTo(1);

		SystemRequestDetails requestDetails =
			SystemRequestDetails.forRequestPartitionId(RequestPartitionId.fromPartitionName(DEFAULT_PARTITION));

		IBundleProvider results = dao.search(map, requestDetails);
		return !results.getResources(0, 1).isEmpty();
	}
}
