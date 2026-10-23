package org.farmsuite.fhir.consent;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.consent.CatalogProperties.TaskCapabilities;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.IdType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Los catálogos de sistema (partición DEFAULT) que dicen qué puede hacer un externo: qué lee y escribe según
 * el tipo de tarea, y qué tipos de organización prestan servicios externos. Se leen del CodeSystem y se
 * guardan unos minutos: un cambio del catálogo tarda ese tiempo en aplicarse. Si no se puede leer, no se
 * concede nada más allá de la tarea y su contexto (un tipo desconocido es un tipo sin propiedades) y el fallo
 * no se guarda, para reintentarlo en la siguiente consulta.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemCatalogs {

	private static final Duration TTL = Duration.ofMinutes(5);
	private static final String TASK_TYPE_ID = "farm-task-type";
	private static final String ORGANIZATION_TYPE_ID = "organization-type";

	private final DaoRegistry daoRegistry;

	private volatile Loaded<Map<String, TaskCapabilities>> taskTypes;
	private volatile Loaded<Set<String>> externalOrganizationTypes;

	private record Loaded<T>(T value, Instant at) {
		boolean fresh() {
			return at.plus(TTL).isAfter(Instant.now());
		}
	}

	public TaskCapabilities taskCapabilities(String taskTypeCode) {
		Loaded<Map<String, TaskCapabilities>> current = taskTypes;
		if (current == null || !current.fresh()) {
			CodeSystem catalog = read(TASK_TYPE_ID);
			if (catalog == null) return TaskCapabilities.NONE; // no se guarda el fallo: se reintenta en la siguiente
			current = new Loaded<>(CatalogProperties.taskCapabilities(catalog), Instant.now());
			taskTypes = current;
		}
		return taskTypeCode == null ? TaskCapabilities.NONE : current.value().getOrDefault(taskTypeCode, TaskCapabilities.NONE);
	}

	public boolean isExternalServiceOrganizationType(String organizationTypeCode) {
		Loaded<Set<String>> current = externalOrganizationTypes;
		if (current == null || !current.fresh()) {
			CodeSystem catalog = read(ORGANIZATION_TYPE_ID);
			if (catalog == null) return false; // no se guarda el fallo: se reintenta en la siguiente
			current = new Loaded<>(CatalogProperties.externalServiceOrganizationTypes(catalog), Instant.now());
			externalOrganizationTypes = current;
		}
		return organizationTypeCode != null && current.value().contains(organizationTypeCode);
	}

	private CodeSystem read(String id) {
		try {
			SystemRequestDetails details = new SystemRequestDetails().setRequestPartitionId(RequestPartitionId.defaultPartition());
			return daoRegistry.getResourceDao(CodeSystem.class).read(new IdType("CodeSystem", id), details);
		} catch (RuntimeException e) {
			log.warn("No se pudo leer el catálogo de sistema CodeSystem/{}: sin propiedades, no se concede nada extra", id, e);
			return null;
		}
	}
}
