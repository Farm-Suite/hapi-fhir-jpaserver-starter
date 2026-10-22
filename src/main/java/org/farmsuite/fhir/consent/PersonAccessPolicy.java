package org.farmsuite.fhir.consent;

import java.time.Instant;

/**
 * Quién puede ver los datos de un paciente según el RGPD, como lógica pura (sin FHIR ni base de
 * datos) para poder probarla entera.
 *
 * <ol>
 *   <li>Un animal o una planta no tienen consentimiento: se rigen por los permisos de la organización.</li>
 *   <li>Una persona la ve, <b>sin consentimiento</b>, la organización que la gestiona
 *       ({@code Patient.managingOrganization}) y las suborganizaciones de esta: acceden por la
 *       relación asistencial.</li>
 *   <li>Cualquier otra organización (una farmacia externa, otra granja) solo con un {@code Consent}
 *       activo concedido a su organización.</li>
 * </ol>
 *
 * Dos casos se permiten a propósito y quedan registrados como hueco, porque no se puede saber si
 * quien accede es externo: una sesión sin organización (p. ej. el dueño del tenant) y una persona
 * sin {@code managingOrganization} (datos anteriores a que fuese obligatorio).
 */
public final class PersonAccessPolicy {

	public enum Decision {
		ALLOW_NOT_A_PERSON,
		ALLOW_CARE_RELATIONSHIP,
		ALLOW_WITHOUT_ORGANIZATION_CONTEXT,
		ALLOW_CONSENT,
		DENY
	}

	/** ¿{@code organizationId} es {@code ancestorId} o cuelga de ella ({@code Organization.partOf})? */
	@FunctionalInterface
	public interface Hierarchy {
		boolean isWithin(String organizationId, String ancestorId);
	}

	/** ¿Hay un Consent activo que permita a esa organización ver ese tipo de recurso de ese paciente ahora? */
	@FunctionalInterface
	public interface ConsentLookup {
		boolean permits(String patientId, String actorOrganizationId, String resourceType, Instant now);
	}

	private PersonAccessPolicy() {
	}

	public static Decision decide(
		PatientFacts patient,
		String actorOrganizationId,
		String resourceType,
		Hierarchy hierarchy,
		ConsentLookup consents,
		Instant now
	) {
		if (patient.kind() != SubjectKind.PERSON) return Decision.ALLOW_NOT_A_PERSON;
		if (isBlank(actorOrganizationId) || isBlank(patient.managingOrganizationId())) {
			return Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT;
		}
		if (hierarchy.isWithin(actorOrganizationId, patient.managingOrganizationId())) {
			return Decision.ALLOW_CARE_RELATIONSHIP;
		}
		return consents.permits(patient.patientId(), actorOrganizationId, resourceType, now)
			? Decision.ALLOW_CONSENT
			: Decision.DENY;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
