package org.farmsuite.fhir.consent;

import org.hl7.fhir.r5.model.CodeSystem;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lo que dicen las propiedades de los CodeSystem de sistema ({@code Infra/execute/fhir/codeSystem}), sin base
 * de datos: qué recursos pueden leer y escribir los responsables externos de cada tipo de tarea
 * ({@code farm-task-type}, propiedades repetibles {@code consent-read} y {@code consent-write}) y qué tipos de
 * organización prestan servicios externos ({@code organization-type}, propiedad booleana {@code external-service}).
 * Un tipo sin propiedades no concede nada más allá de la tarea y su contexto.
 */
public final class CatalogProperties {

	public static final String TASK_TYPE_SYSTEM = "https://farmsuite.org/fhir/DEFAULT/CodeSystem/farm-task-type";
	public static final String ORGANIZATION_TYPE_SYSTEM = "https://farmsuite.org/fhir/DEFAULT/CodeSystem/organization-type";

	/** Tipos de recurso que se pueden leer y escribir sobre el paciente de una tarea de un tipo. */
	public record TaskCapabilities(Set<String> read, Set<String> write) {
		public static final TaskCapabilities NONE = new TaskCapabilities(Set.of(), Set.of());
	}

	private CatalogProperties() {
	}

	/** Por código de tipo de tarea; los de la propia lista de conceptos, también los anidados. */
	public static Map<String, TaskCapabilities> taskCapabilities(CodeSystem taskTypes) {
		Map<String, TaskCapabilities> byCode = new HashMap<>();
		if (taskTypes != null) collect(taskTypes.getConcept(), byCode);
		return byCode;
	}

	private static void collect(java.util.List<CodeSystem.ConceptDefinitionComponent> concepts, Map<String, TaskCapabilities> byCode) {
		for (CodeSystem.ConceptDefinitionComponent concept : concepts) {
			Set<String> read = new HashSet<>();
			Set<String> write = new HashSet<>();
			for (CodeSystem.ConceptPropertyComponent property : concept.getProperty()) {
				if (!property.hasValueCodeType()) continue;
				String type = property.getValueCodeType().getValue();
				if ("consent-read".equals(property.getCode())) read.add(type);
				if ("consent-write".equals(property.getCode())) write.add(type);
			}
			// Lo que se escribe, se puede leer: no se escribe sin ver.
			read.addAll(write);
			byCode.put(concept.getCode(), new TaskCapabilities(Set.copyOf(read), Set.copyOf(write)));
			collect(concept.getConcept(), byCode);
		}
	}

	/** Códigos de tipo de organización marcados como servicio externo. */
	public static Set<String> externalServiceOrganizationTypes(CodeSystem organizationTypes) {
		Set<String> codes = new HashSet<>();
		if (organizationTypes != null) collectExternal(organizationTypes.getConcept(), codes);
		return codes;
	}

	private static void collectExternal(java.util.List<CodeSystem.ConceptDefinitionComponent> concepts, Set<String> codes) {
		for (CodeSystem.ConceptDefinitionComponent concept : concepts) {
			boolean external = concept.getProperty().stream()
				.anyMatch(p -> "external-service".equals(p.getCode()) && p.hasValueBooleanType() && Boolean.TRUE.equals(p.getValueBooleanType().getValue()));
			if (external) codes.add(concept.getCode());
			collectExternal(concept.getConcept(), codes);
		}
	}
}
