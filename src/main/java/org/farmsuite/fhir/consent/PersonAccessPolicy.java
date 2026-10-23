package org.farmsuite.fhir.consent;

import org.farmsuite.fhir.consent.ConsentMatcher.Access;
import org.farmsuite.fhir.consent.ConsentMatcher.Actor;

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
 *   <li>Cualquier otro actor (una farmacia externa, otra granja, un médico autónomo) solo con un
 *       {@code Consent} activo concedido a su practitioner o a su organización, y para el modo que pida
 *       (leer, escribir; borrar nunca).</li>
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

	/** ¿Hay un Consent activo que permita a ese actor este acceso a ese tipo de recurso de ese paciente ahora? */
	@FunctionalInterface
	public interface ConsentLookup {
		boolean permits(String patientId, Actor actor, String resourceType, Access access, Instant now);
	}

	private PersonAccessPolicy() {
	}

	public static Decision decide(
		PatientFacts patient,
		Actor actor,
		String resourceType,
		Access access,
		Hierarchy hierarchy,
		ConsentLookup consents,
		Instant now
	) {
		return decide(patient, actor, resourceType, access, hierarchy, consents, now, false);
	}

	/** {@code requireOrganization}: una sesión sin organización se deniega en vez de permitirse. */
	public static Decision decide(
		PatientFacts patient,
		Actor actor,
		String resourceType,
		Access access,
		Hierarchy hierarchy,
		ConsentLookup consents,
		Instant now,
		boolean requireOrganization
	) {
		String actorOrganizationId = actor.organizationId();
		if (patient.unresolved()) return Decision.DENY;
		if (patient.kind() != SubjectKind.PERSON) return Decision.ALLOW_NOT_A_PERSON;
		if (requireOrganization && isBlank(actorOrganizationId)) return Decision.DENY;
		if (isBlank(actorOrganizationId) || isBlank(patient.managingOrganizationId())) {
			return Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT;
		}
		if (hierarchy.isWithin(actorOrganizationId, patient.managingOrganizationId())) {
			return Decision.ALLOW_CARE_RELATIONSHIP;
		}
		return consents.permits(patient.patientId(), actor, resourceType, access, now)
			? Decision.ALLOW_CONSENT
			: Decision.DENY;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
