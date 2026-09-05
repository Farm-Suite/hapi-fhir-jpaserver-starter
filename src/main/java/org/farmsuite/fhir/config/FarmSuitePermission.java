package org.farmsuite.fhir.config;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una entrada del claim {@code permissions} del JWT ya resuelta en login por
 * {@code BasePermissionsAuthenticator} a partir de la plantilla de rol del tenant.
 *
 * <p>Formato esperado: {@code "<ResourceType>.<acciones>"}, ej. {@code "Patient.rs"}
 * (read+search sobre Patient), {@code "Observation.rscud"} (todo sobre Observation).
 * {@code resourceType} admite el comodín {@code "*"} ({@code "*.rscud"} = todas las acciones
 * sobre cualquier resourceType, pero siempre scoped al tenant vía {@code forTenantIds} — no
 * es un {@code allowAll()} global) — pensado para el rol "owner"/"admin" de un tenant.</p>
 */
record FarmSuitePermission(String resourceType, Set<PermissionAction> actions) {

	static final String WILDCARD_RESOURCE_TYPE = "*";

	private static final Pattern PATTERN = Pattern.compile("^(\\*|[A-Za-z]+)\\.([a-z]+)$");

	boolean isWildcard() {
		return WILDCARD_RESOURCE_TYPE.equals(resourceType);
	}

	static FarmSuitePermission parse(String raw) {
		Matcher matcher = PATTERN.matcher(raw);
		if (!matcher.matches()) {
			throw new IllegalArgumentException(
				"Permiso con formato inválido: '" + raw + "' (esperado 'ResourceType.acciones', ej. 'Patient.rs')");
		}
		return new FarmSuitePermission(matcher.group(1), PermissionAction.parse(matcher.group(2)));
	}
}
