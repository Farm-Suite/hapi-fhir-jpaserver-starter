package org.farmsuite.fhir.consent;

import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Period;
import org.hl7.fhir.r5.model.Reference;

import java.time.Instant;

/**
 * Decide si un {@link Consent} concreto permite a una organización ver un tipo de recurso ahora.
 * Lógica pura sobre el recurso, sin base de datos. Un Consent permite solo si:
 *
 * <ul>
 *   <li>está {@code active} y su {@code decision} es {@code permit} (un {@code deny}, o sin
 *       decisión, nunca permite: lo ambiguo se trata como no concedido);</li>
 *   <li>su {@code grantee} incluye a esa organización;</li>
 *   <li>su {@code period} (si lo tiene) contiene el momento actual — una revocación se expresa
 *       pasándolo a {@code inactive} o cerrando el periodo;</li>
 *   <li>y, si tiene {@code provision}, al menos una cubre ese tipo de recurso (sin
 *       {@code resourceType} cubre todos) y su periodo contiene el momento actual.</li>
 * </ul>
 *
 * Las {@code provision} anidadas (excepciones) no se evalúan todavía.
 */
public final class ConsentMatcher {

	private ConsentMatcher() {
	}

	public static boolean permits(Consent consent, String actorOrganizationId, String resourceType, Instant now) {
		if (consent == null || consent.getStatus() != Consent.ConsentState.ACTIVE) return false;
		if (consent.getDecision() != Enumerations.ConsentProvisionType.PERMIT) return false;
		if (!isGrantee(consent, actorOrganizationId)) return false;
		if (!contains(consent.getPeriod(), now)) return false;
		if (consent.getProvision().isEmpty()) return true;

		return consent.getProvision().stream().anyMatch(provision ->
			contains(provision.getPeriod(), now) && covers(provision.getResourceType(), resourceType));
	}

	private static boolean isGrantee(Consent consent, String organizationId) {
		if (organizationId == null || organizationId.isBlank()) return false;
		for (Reference grantee : consent.getGrantee()) {
			if (grantee.hasReference()
				&& "Organization".equals(grantee.getReferenceElement().getResourceType())
				&& organizationId.equals(grantee.getReferenceElement().getIdPart())) {
				return true;
			}
		}
		return false;
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
