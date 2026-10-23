package org.farmsuite.fhir.consent;

import org.farmsuite.fhir.consent.CatalogProperties.TaskCapabilities;
import org.farmsuite.fhir.consent.ConsentMatcher.Access;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Qué tiene que cubrir el Consent para asignar una tarea a un externo, como lógica pura. Siempre la ficha del
 * paciente (para saber de quién es); y, según el tipo de tarea, lo que lee y lo que escribe. Lo que se escribe
 * se pide como escritura, que ya incluye leerlo.
 */
public final class ExternalAssignmentPolicy {

	/** Un tipo de recurso con el acceso que debe cubrir el Consent. */
	public record Requirement(String resourceType, Access access) {
		public String describe() {
			return resourceType + (access == Access.WRITE ? " (lectura y escritura)" : " (lectura)");
		}
	}

	private ExternalAssignmentPolicy() {
	}

	public static List<Requirement> requirements(TaskCapabilities capabilities) {
		List<Requirement> required = new ArrayList<>();
		required.add(new Requirement("Patient", Access.READ));
		for (String type : new TreeSet<>(capabilities.read())) {
			if (!capabilities.write().contains(type) && !"Patient".equals(type)) required.add(new Requirement(type, Access.READ));
		}
		for (String type : new TreeSet<>(capabilities.write())) {
			required.add(new Requirement(type, Access.WRITE));
		}
		return required;
	}

	/** Lo que el Consent actual no cubre, en el orden de {@link #requirements}. */
	public static List<Requirement> missing(List<Requirement> required, Predicate<Requirement> covered) {
		return required.stream().filter(requirement -> !covered.test(requirement)).toList();
	}
}
