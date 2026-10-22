package org.farmsuite.fhir.usecase;

import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;

import java.util.Optional;
import java.util.function.Function;

/**
 * Quién tiene asignada una Task y qué organización la creó, tal como se espeja en
 * Administration-MIC. En FHIR {@code Task.owner} es una sola referencia, pero admite tres formas
 * que cubren las combinaciones posibles:
 *
 * <ul>
 *   <li>{@code Practitioner/x} → solo profesional.</li>
 *   <li>{@code Organization/y} → solo organización (una cola que ella delega luego).</li>
 *   <li>{@code PractitionerRole/z} → profesional <b>y</b> organización: el rol ya enlaza a uno con
 *       la otra, así que se resuelve leyéndolo.</li>
 * </ul>
 *
 * {@code Task.requester} con {@code Organization/c} es la organización que la crea; el profesional
 * concreto que la escribió queda en el Provenance.
 */
public record TaskAssignment(String practitionerId, String organizationId, String createdByOrganizationId) {

	private static final String PRACTITIONER = "Practitioner";
	private static final String ORGANIZATION = "Organization";
	private static final String PRACTITIONER_ROLE = "PractitionerRole";

	/**
	 * @param roleReader lee un PractitionerRole por id en la partición de la Task; puede lanzar si no existe
	 * @return vacío si la Task no tiene un owner espejable (sin owner, otro tipo de recurso, o un
	 *         PractitionerRole que no se puede leer o no enlaza a un profesional)
	 */
	public static Optional<TaskAssignment> from(Task task, Function<String, PractitionerRole> roleReader) {
		if (!task.hasOwner() || !task.getOwner().hasReference()) return Optional.empty();

		String type = task.getOwner().getReferenceElement().getResourceType();
		String id = task.getOwner().getReferenceElement().getIdPart();
		if (id == null || id.isBlank()) return Optional.empty();

		String practitionerId;
		String organizationId;
		switch (type == null ? "" : type) {
			case PRACTITIONER -> {
				practitionerId = id;
				organizationId = null;
			}
			case ORGANIZATION -> {
				practitionerId = null;
				organizationId = id;
			}
			case PRACTITIONER_ROLE -> {
				PractitionerRole role;
				try {
					role = roleReader.apply(id);
				} catch (RuntimeException e) {
					return Optional.empty();
				}
				practitionerId = idOf(role == null ? null : role.getPractitioner(), PRACTITIONER);
				organizationId = idOf(role == null ? null : role.getOrganization(), ORGANIZATION);
				if (practitionerId == null) return Optional.empty();
			}
			default -> {
				return Optional.empty();
			}
		}
		return Optional.of(new TaskAssignment(practitionerId, organizationId, idOf(task.getRequester(), ORGANIZATION)));
	}

	private static String idOf(Reference reference, String expectedType) {
		if (reference == null || !reference.hasReference()) return null;
		if (!expectedType.equals(reference.getReferenceElement().getResourceType())) return null;
		String id = reference.getReferenceElement().getIdPart();
		return id == null || id.isBlank() ? null : id;
	}
}
