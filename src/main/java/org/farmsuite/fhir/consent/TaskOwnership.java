package org.farmsuite.fhir.consent;

import org.farmsuite.fhir.consent.ConsentMatcher.Actor;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;

import java.util.function.Function;

/**
 * ¿Es esta tarea del actor? Es lo que decide qué tareas ve y actualiza un externo (un trabajador al que
 * se la han asignado), sin darle el resto de las del tenant. El responsable ({@code Task.owner}) puede ser:
 * un profesional ({@code Practitioner/x}), un rol suyo ({@code PractitionerRole/r}, cuyo profesional se
 * resuelve con {@code practitionerOfRole}) o una organización ({@code Organization/o}: su cola, de la que
 * es parte quien actúa por ella).
 */
public final class TaskOwnership {

	private TaskOwnership() {
	}

	public static boolean ownedBy(Task task, Actor actor, Function<String, String> practitionerOfRole) {
		if (task == null || !task.hasOwner() || !task.getOwner().hasReference()) return false;
		Reference owner = task.getOwner();
		String type = owner.getReferenceElement().getResourceType();
		String id = owner.getReferenceElement().getIdPart();
		if (id == null || id.isBlank()) return false;
		return switch (type == null ? "" : type) {
			case "Practitioner" -> id.equals(actor.practitionerId());
			case "PractitionerRole" -> actor.practitionerId() != null && actor.practitionerId().equals(practitionerOfRole.apply(id));
			case "Organization" -> id.equals(actor.organizationId());
			default -> false;
		};
	}
}
