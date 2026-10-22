package org.farmsuite.fhir.consent;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentOutcome;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentContextServices;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.consent.PersonAccessPolicy.Decision;
import org.farmsuite.fhir.interceptors.ClinicalAccessAuditInterceptor;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.AuditEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Aplica {@link PersonAccessPolicy} a cada recurso clínico que una petición va a devolver (HAPI
 * llama a {@link #canSeeResource} por recurso, también en búsquedas, donde {@code REJECT} lo quita
 * del resultado). La organización de quien accede es la de su sesión: tras un token exchange es la
 * organización para la que se emitió el token, que es justo lo que distingue a una farmacia externa
 * de la organización que gestiona al paciente.
 *
 * <p>No se aplica a la cuenta de servicio ni a las lecturas internas del servidor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarmSuiteConsentService implements IConsentService {

	private final UserSessionRepository userSessionRepository;
	private final PatientSubjectResolver patientSubjectResolver;
	private final ConsentFinder consentFinder;
	private final DaoRegistry daoRegistry;
	private final TenantRequests tenantRequests;

	private static final String DENIED_KEY = "farmsuite.consent.denied";

	@Override
	public ConsentOutcome startOperation(RequestDetails requestDetails, IConsentContextServices contextServices) {
		if (requestDetails instanceof SystemRequestDetails || userSessionRepository.isAdmin()) {
			return ConsentOutcome.AUTHORIZED;
		}
		return ConsentOutcome.PROCEED;
	}

	@Override
	public ConsentOutcome canSeeResource(RequestDetails requestDetails, IBaseResource resource, IConsentContextServices contextServices) {
		if (!PatientSubjectResolver.PATIENT_LINKED_TYPES.contains(resource.fhirType())) return ConsentOutcome.PROCEED;

		String tenantId = requestDetails.getTenantId();
		if (tenantId == null || tenantId.isBlank()) return ConsentOutcome.PROCEED;

		Optional<PatientFacts> patient = patientSubjectResolver.factsOf(resource, tenantId, requestDetails);
		if (patient.isEmpty()) return ConsentOutcome.PROCEED; // sin paciente identificable no hay dato personal que proteger

		String actorOrganizationId = userSessionRepository.getUserSession().map(UserSessionModel::getOrganizationId).orElse(null);
		Decision decision = PersonAccessPolicy.decide(
			patient.get(),
			actorOrganizationId,
			resource.fhirType(),
			(organization, ancestor) -> patientSubjectResolver.isWithin(tenantId, organization, ancestor, requestDetails),
			(patientId, organization, type, now) -> permitsCached(tenantId, patientId, organization, type, now, requestDetails),
			Instant.now());

		if (decision == Decision.DENY) {
			log.warn("Acceso DENEGADO por falta de Consent: organization={} practitioner={} -> {}/{} (paciente {}, tenant {})",
				actorOrganizationId, userSessionRepository.getUserSession().map(UserSessionModel::getPractitionerId).orElse(null),
				resource.fhirType(), resource.getIdElement().getIdPart(), patient.get().patientId(), tenantId);
			deniedIn(requestDetails).add(resource.fhirType() + "/" + resource.getIdElement().getIdPart());
			return ConsentOutcome.REJECT;
		}
		if (decision == Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT) {
			log.debug("Acceso permitido sin poder clasificarlo (sin organización en la sesión o paciente sin managingOrganization): {}/{}",
				resource.fhirType(), resource.getIdElement().getIdPart());
		}
		return ConsentOutcome.PROCEED;
	}

	private boolean permitsCached(String tenantId, String patientId, String organization, String type, Instant now, RequestDetails requestDetails) {
		String key = "farmsuite.consent." + tenantId + "/" + patientId + "/" + organization + "/" + type;
		Object cached = requestDetails.getUserData().get(key);
		if (cached instanceof Boolean allowed) return allowed;
		boolean allowed = consentFinder.permits(tenantId, patientId, organization, type, now);
		requestDetails.getUserData().put(key, allowed);
		return allowed;
	}

	@Override
	public void completeOperationSuccess(RequestDetails requestDetails, IConsentContextServices contextServices) {
		auditDenied(requestDetails);
	}

	@Override
	public void completeOperationFailure(RequestDetails requestDetails, BaseServerResponseException exception, IConsentContextServices contextServices) {
		auditDenied(requestDetails);
	}

	/** Recursos denegados en esta petición, acumulados para escribir un único AuditEvent al terminar. */
	@SuppressWarnings("unchecked")
	private List<String> deniedIn(RequestDetails requestDetails) {
		return (List<String>) requestDetails.getUserData().computeIfAbsent(DENIED_KEY, k -> new ArrayList<String>());
	}

	/**
	 * Una petición que deniega mil recursos deja <b>un</b> AuditEvent, no mil. Best-effort a
	 * propósito: el acceso ya está denegado, un fallo al auditarlo no debe convertir la respuesta en
	 * un error ni ocultar la denegación.
	 */
	private void auditDenied(RequestDetails requestDetails) {
		Object denied = requestDetails.getUserData().remove(DENIED_KEY);
		if (!(denied instanceof List<?> references) || references.isEmpty()) return;

		String tenantId = requestDetails.getTenantId();
		var session = userSessionRepository.getUserSession();
		try {
			AuditEvent event = ClinicalAccessAuditInterceptor.buildDeniedAuditEvent(
				references.stream().map(String::valueOf).toList(),
				session.map(UserSessionModel::getPractitionerId).orElse(null),
				session.map(UserSessionModel::getOrganizationId).orElse(null),
				new Date());
			daoRegistry.getResourceDao(AuditEvent.class).create(event, tenantRequests.systemIn(tenantId));
		} catch (RuntimeException e) {
			log.error("No se pudo auditar el acceso denegado ({} recursos, tenant={})", references.size(), tenantId, e);
		}
	}
}
