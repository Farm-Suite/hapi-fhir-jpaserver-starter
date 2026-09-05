package org.farmsuite.fhir.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilder;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilderRuleOp;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilderRuleOpClassifier;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Practitioner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Reglas de autorización del server FHIR según la plantilla de rol resuelta en login.
 *
 * <p>Cuenta de servicio (rol de realm {@code SERVICE_ACCOUNT_FARMSUITE}, ver
 * {@code UserSessionRepository#isAdmin()} en sso-interceptor) → sin restricciones. La fuente
 * de verdad es el rol de realm ya asignado en Keycloak al service-account del client, no una
 * allowlist propia acá.</p>
 *
 * <p>Usuario humano → reglas allow por resourceType+acción a partir del claim {@code permissions}
 * del JWT, ya expandido en login por {@code BasePermissionsAuthenticator} desde la plantilla de
 * rol del tenant. Sin lookup externo por request: todo sale del JWT ya validado.
 * {@code forTenantIds(tenantId)} refuerza la partición de tenant además del segmento de path
 * {@code base/tenant/Resource} que ya resuelve {@link org.farmsuite.fhir.interceptors.RequestTenantInterceptor}
 * (defensa en profundidad).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarmSuiteRules extends AuthorizationInterceptor {

	/** Practitioner siempre vive en la partición DEFAULT (ver RequestTenantInterceptor),
	 *  nunca en la del tenant de la sesión. */
	private static final String DEFAULT_PARTITION = "DEFAULT";

	private final UserSessionRepository userSessionRepository;
	private final FhirContext fhirContext;

	@Override
	public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
		if (userSessionRepository.isAdmin()) {
			log.debug("Cuenta de servicio (SERVICE_ACCOUNT_FARMSUITE) — sin restricciones de permisos");
			return new RuleBuilder().allowAll().build();
		}

		return userSessionRepository.getUserSession()
			.map(this::buildRulesForSession)
			.orElseGet(() -> new RuleBuilder().denyAll("no-session").build());
	}

	private List<IAuthRule> buildRulesForSession(UserSessionModel session) {
		String tenantId = session.getTenantId();
		List<String> rawPermissions = session.getPermission();

		if (tenantId == null || tenantId.isBlank() || rawPermissions == null || rawPermissions.isEmpty()) {
			log.warn("Usuario '{}' sin tenantId o sin permisos resueltos en el token — denegando todo",
				session.getUsername());
			return new RuleBuilder().denyAll("no-permissions").build();
		}

		IAuthRuleBuilder builder = new RuleBuilder();
		builder = applyPractitionerSelfServiceRules(builder, session);

		for (String raw : rawPermissions) {
			builder = applyPermission(builder, raw, tenantId);
		}

		return builder.denyAll("default-deny").build();
	}

	/**
	 * Invariante estructural de Practitioner, independiente de lo que diga el claim
	 * {@code permissions} — ninguna plantilla de rol puede aflojar esto: nadie salvo la cuenta
	 * de servicio (ya cubierta por {@code isAdmin()} en {@link #buildRuleList}) puede crear un
	 * Practitioner, y un usuario humano solo puede actualizar el suyo propio. Se evalúan antes
	 * que el bucle de permisos porque HAPI aplica la primera regla de la lista que matchee la
	 * request (ver {@code AuthorizationInterceptor#applyRulesAndFailIfDeny}) — así ninguna
	 * plantilla de rol puede sobreescribir esto por accidente.
	 */
	private IAuthRuleBuilder applyPractitionerSelfServiceRules(IAuthRuleBuilder builder, UserSessionModel session) {
		builder = builder.deny().create()
			.resourcesOfType(Practitioner.class).withAnyId()
			.andThen();

		String practitionerId = session.getPractitionerId();
		if (practitionerId != null && !practitionerId.isBlank()) {
			builder = builder.allow().write()
				.resourcesOfType(Practitioner.class)
				.inCompartment("Practitioner", new IdType("Practitioner", practitionerId))
				.forTenantIds(DEFAULT_PARTITION)
				.andThen();
		}

		return builder;
	}

	private IAuthRuleBuilder applyPermission(IAuthRuleBuilder builder, String raw, String tenantId) {
		final FarmSuitePermission permission;
		try {
			permission = FarmSuitePermission.parse(raw);
		} catch (IllegalArgumentException e) {
			log.error("Permiso mal formado en el claim del token, se ignora: '{}'", raw, e);
			return builder;
		}

		final boolean wildcard = permission.isWildcard();
		Class<? extends IBaseResource> resourceClass = null;
		if (!wildcard) {
			try {
				resourceClass = fhirContext.getResourceDefinition(permission.resourceType()).getImplementingClass();
			} catch (RuntimeException e) {
				log.error("Tipo de recurso FHIR desconocido en permiso, se ignora: '{}'", raw, e);
				return builder;
			}
		}

		Set<PermissionAction> actions = permission.actions();

		// HAPI no distingue "leer una instancia" de "search" a nivel de regla: ambas caen
		// bajo la categoría READ del AuthorizationInterceptor.
		if (actions.contains(PermissionAction.READ) || actions.contains(PermissionAction.SEARCH)) {
			builder = applyTo(builder.allow().read(), wildcard, resourceClass)
				.withAnyId()
				.forTenantIds(tenantId)
				.andThen();
		}

		// write() en HAPI cubre create+update juntos, no hay verbo "solo update": si el
		// permiso pide UPDATE (con o sin CREATE) se usa write(); si pide solo CREATE se
		// usa el verbo create() más estrecho para no conceder update de más.
		if (actions.contains(PermissionAction.UPDATE)) {
			builder = applyTo(builder.allow().write(), wildcard, resourceClass)
				.withAnyId()
				.forTenantIds(tenantId)
				.andThen();
		} else if (actions.contains(PermissionAction.CREATE)) {
			builder = applyTo(builder.allow().create(), wildcard, resourceClass)
				.withAnyId()
				.forTenantIds(tenantId)
				.andThen();
		}

		if (actions.contains(PermissionAction.DELETE)) {
			builder = applyTo(builder.allow().delete(), wildcard, resourceClass)
				.withAnyId()
				.forTenantIds(tenantId)
				.andThen();
		}

		if (actions.contains(PermissionAction.PATCH)) {
			// IAuthRuleBuilderPatch solo expone allRequests(): HAPI no permite acotar patch
			// por resourceType/tenant en este punto de la API. No se implementa para no
			// conceder patch global a partir de un permiso pensado para un solo recurso.
			log.warn("Permiso '{}' pide PATCH, que HAPI no puede acotar por resourceType/tenant — se ignora", raw);
		}

		return builder;
	}

	/** {@code wildcard} usa {@code allResources()}; si no, {@code resourcesOfType(resourceClass)}. */
	private IAuthRuleBuilderRuleOpClassifier applyTo(
			IAuthRuleBuilderRuleOp op, boolean wildcard, Class<? extends IBaseResource> resourceClass) {
		return wildcard ? op.allResources() : op.resourcesOfType(resourceClass);
	}
}
