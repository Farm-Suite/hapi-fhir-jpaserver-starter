package org.farmsuite.fhir.consent;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import lombok.RequiredArgsConstructor;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Cómo entra quien hace la petición al tenant de la URL. Lo usan {@code FarmSuiteRules} (que construye
 * las reglas antes de resolver la partición) y {@code RequestTenantInterceptor}, así que se calcula una
 * sola vez por petición.
 *
 * <ul>
 *   <li>{@link Kind#OWN_TENANT}: es el tenant de su sesión.</li>
 *   <li>{@link Kind#BY_CONSENT}: no lo es, pero hay un Consent activo en ese tenant a favor de su
 *       practitioner o de su organización. Es la vía de los externos (un médico autónomo, una farmacia):
 *       sustituye al token exchange.</li>
 *   <li>{@link Kind#NONE}: no entra.</li>
 *   <li>{@link Kind#NOT_SCOPED}: la comprobación no aplica (cuenta de servicio, llamada interna, sin
 *       tenant o {@code DEFAULT} en la URL, o sin sesión: eso lo resuelve {@code FarmSuiteRules}).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TenantAccessService {

	public enum Kind { NOT_SCOPED, OWN_TENANT, BY_CONSENT, NONE }

	private static final String CACHE_KEY = "farmsuite.tenant-access";

	private final UserSessionRepository userSessionRepository;
	private final ConsentFinder consentFinder;

	public Kind resolve(RequestDetails requestDetails) {
		if (requestDetails == null || requestDetails instanceof SystemRequestDetails || userSessionRepository.isAdmin()) {
			return Kind.NOT_SCOPED;
		}
		String tenantId = requestDetails.getTenantId();
		if (tenantId == null || tenantId.isBlank() || "DEFAULT".equalsIgnoreCase(tenantId)) return Kind.NOT_SCOPED;

		Optional<UserSessionModel> session = userSessionRepository.getUserSession();
		if (session.isEmpty()) return Kind.NOT_SCOPED;

		Object cached = requestDetails.getUserData().get(CACHE_KEY + "/" + tenantId);
		if (cached instanceof Kind kind) return kind;

		Kind kind = compute(session.get(), tenantId);
		requestDetails.getUserData().put(CACHE_KEY + "/" + tenantId, kind);
		return kind;
	}

	private Kind compute(UserSessionModel session, String tenantId) {
		if (session.canAccessTenant(tenantId)) return Kind.OWN_TENANT;
		ConsentMatcher.Actor actor = new ConsentMatcher.Actor(session.getPractitionerId(), session.getOrganizationId());
		return consentFinder.hasActiveGrant(tenantId, actor, Instant.now()) ? Kind.BY_CONSENT : Kind.NONE;
	}
}
