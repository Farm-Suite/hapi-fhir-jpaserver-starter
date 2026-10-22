package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.rest.server.interceptor.consent.ConsentInterceptor;
import org.farmsuite.fhir.consent.FarmSuiteConsentService;
import org.springframework.stereotype.Component;

/**
 * Engancha {@link FarmSuiteConsentService} al servidor: HAPI le pregunta por cada recurso clínico
 * que una petición va a devolver. Hay que registrarlo en {@code hapi.fhir.custom-interceptor-classes}
 * (application.yaml y chart/values.yaml).
 */
@Component
public class FarmSuiteConsentInterceptor extends ConsentInterceptor {

	public FarmSuiteConsentInterceptor(FarmSuiteConsentService consentService) {
		super(consentService);
	}
}
