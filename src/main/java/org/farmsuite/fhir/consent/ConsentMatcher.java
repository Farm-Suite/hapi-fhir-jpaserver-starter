package org.farmsuite.fhir.consent;

import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Period;
import org.hl7.fhir.r5.model.Reference;

import java.time.Instant;

/**
 * Decide si un {@link Consent} concreto permite a un actor ver o escribir un tipo de recurso ahora.
 * Lógica pura sobre el recurso, sin base de datos. Un Consent permite solo si:
 *
 * <ul>
 *   <li>está {@code active} y su {@code decision} es {@code permit} (un {@code deny}, o sin
 *       decisión, nunca permite: lo ambiguo se trata como no concedido);</li>
 *   <li>su {@code grantee} incluye al actor: su practitioner o la organización en cuyo nombre actúa;</li>
 *   <li>su {@code period} (si lo tiene) contiene el momento actual — una revocación se expresa
 *       pasándolo a {@code inactive} o cerrando el periodo;</li>
 *   <li>y, si tiene {@code provision}, al menos una cubre ese tipo de recurso (sin
 *       {@code resourceType} cubre todos) y su periodo contiene el momento actual.</li>
 * </ul>
 *
 * <p>El modo lo marca {@code provision.action}: sin acción, o con {@code read}, solo lectura; con
 * {@code write}, escritura y también lectura (no se puede escribir sin ver). Borrar no se concede nunca
 * por Consent. Un Consent sin
 * {@code provision} cubre la lectura de todo pero nunca la escritura: escribir exige concederlo de forma
 * explícita.
 *
 * <p>Las {@code provision} anidadas (excepciones) no se evalúan todavía.
 */
public final class ConsentMatcher {

	private ConsentMatcher() {
	}

	public static final String ACTION_READ = "read";
	public static final String ACTION_WRITE = "write";

	/** Qué intenta hacer el actor con el recurso. */
	public enum Access { READ, WRITE, DELETE }

	/** Quién accede: su practitioner y/o la organización en cuyo nombre actúa (cualquiera puede faltar). */
	public record Actor(String practitionerId, String organizationId) {
		public static Actor ofOrganization(String organizationId) {
			return new Actor(null, organizationId);
		}
	}

	/** Lectura por una organización: la forma original, sin practitioner. */
	public static boolean permits(Consent consent, String actorOrganizationId, String resourceType, Instant now) {
		return permits(consent, Actor.ofOrganization(actorOrganizationId), resourceType, Access.READ, now);
	}

	public static boolean permits(Consent consent, Actor actor, String resourceType, Access access, Instant now) {
		if (consent == null || consent.getStatus() != Consent.ConsentState.ACTIVE) return false;
		if (consent.getDecision() != Enumerations.ConsentProvisionType.PERMIT) return false;
		if (!isGrantee(consent, actor)) return false;
		if (!contains(consent.getPeriod(), now)) return false;
		if (consent.getProvision().isEmpty()) return access == Access.READ;

		return consent.getProvision().stream().anyMatch(provision ->
			contains(provision.getPeriod(), now)
				&& covers(provision.getResourceType(), resourceType)
				&& allows(provision.getAction(), access));
	}

	/**
	 * ¿Concede este Consent, ahora, acceso al actor? Es lo que le deja entrar a un tenant que no es el
	 * suyo, sin mirar tipos de recurso ni modo (eso lo decide {@link #permits} para cada recurso): activo,
	 * {@code permit}, a su practitioner u organización, con el periodo del Consent vigente y, si tiene
	 * provisiones, alguna vigente.
	 */
	public static boolean isActiveGrant(Consent consent, Actor actor, Instant now) {
		if (consent == null || consent.getStatus() != Consent.ConsentState.ACTIVE) return false;
		if (consent.getDecision() != Enumerations.ConsentProvisionType.PERMIT) return false;
		if (!isGrantee(consent, actor)) return false;
		if (!contains(consent.getPeriod(), now)) return false;
		return consent.getProvision().isEmpty()
			|| consent.getProvision().stream().anyMatch(provision -> contains(provision.getPeriod(), now));
	}

	private static boolean isGrantee(Consent consent, Actor actor) {
		for (Reference grantee : consent.getGrantee()) {
			if (!grantee.hasReference()) continue;
			String type = grantee.getReferenceElement().getResourceType();
			String id = grantee.getReferenceElement().getIdPart();
			if ("Organization".equals(type) && isSame(id, actor.organizationId())) return true;
			if ("Practitioner".equals(type) && isSame(id, actor.practitionerId())) return true;
		}
		return false;
	}

	private static boolean isSame(String grantedId, String actorId) {
		return actorId != null && !actorId.isBlank() && actorId.equals(grantedId);
	}

	private static boolean allows(java.util.List<CodeableConcept> actions, Access access) {
		if (access == Access.READ) return true; // sin acción, o con read o write, se puede leer
		if (access == Access.DELETE) return false; // borrar nunca se concede por Consent
		return actions.stream().flatMap(a -> a.getCoding().stream()).anyMatch(c -> ACTION_WRITE.equals(c.getCode()));
	}

	private static boolean covers(java.util.List<Coding> resourceTypes, String resourceType) {
		return resourceTypes.isEmpty() || resourceTypes.stream().anyMatch(c -> resourceType.equals(c.getCode()));
	}

	private static boolean contains(Period period, Instant now) {
		if (period == null || period.isEmpty()) return true;
		if (period.hasStart() && period.getStart().toInstant().isAfter(now)) return false;
		return !period.hasEnd() || !period.getEnd().toInstant().isBefore(now);
	}
}
