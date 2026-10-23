package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import lombok.RequiredArgsConstructor;
import org.farmsuite.fhir.consent.FarmSuiteConsentService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

/**
 * El Consent de {@link FarmSuiteConsentInterceptor} solo controla lecturas; esto aplica la misma
 * regla a crear, modificar y borrar recursos clínicos de una persona, para que una organización
 * externa (p. ej. una farmacia) no escriba sobre alguien que no le ha dado su consentimiento.
 * Hay que registrarlo en {@code hapi.fhir.custom-interceptor-classes} (application.yaml y chart/values.yaml).
 */
@Interceptor
@Component
@RequiredArgsConstructor
public class PersonWriteConsentInterceptor {

	private final FarmSuiteConsentService consentService;

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void onCreated(IBaseResource theResource, RequestDetails theRequestDetails) {
		check(theResource, theRequestDetails);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void onUpdated(IBaseResource theOldResource, IBaseResource theNewResource, RequestDetails theRequestDetails) {
		check(theOldResource, theRequestDetails); // no se puede quitar el paciente para esquivar el control
		check(theNewResource, theRequestDetails);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_DELETED)
	public void onDeleted(IBaseResource theResource, RequestDetails theRequestDetails) {
		check(theResource, theRequestDetails);
	}

	private void check(IBaseResource resource, RequestDetails requestDetails) {
		if (resource == null || consentService.mayWrite(requestDetails, resource)) return;
		throw new ForbiddenOperationException(
			"Sin consentimiento de la persona para modificar " + resource.fhirType() + "/" + resource.getIdElement().getIdPart());
	}
}
