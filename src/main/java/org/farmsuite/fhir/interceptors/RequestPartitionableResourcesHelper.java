package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.jpa.partition.RequestPartitionHelperSvc;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
public class RequestPartitionableResourcesHelper extends RequestPartitionHelperSvc {


	// Tipos que HAPI marca como no-particionables por defecto (ver
	// BaseRequestPartitionHelperSvc.NON_PARTITIONABLE_RESOURCE_NAMES) pero que
	// FarmSuite necesita aislados por tenant.
	private static final Set<String> FORCE_PARTITIONABLE = Set.of(
		"CodeSystem",
		"ConceptMap",
		"StructureDefinition",
		"ValueSet"
	);


	@Override
	public boolean isResourcePartitionable(String theResourceType) {
		final var isPartitionable = FORCE_PARTITIONABLE.contains(theResourceType);
		log.debug("Checking if resource type {} is partitionable in {}. Paritionable: {}", theResourceType, FORCE_PARTITIONABLE, isPartitionable);
		if (isPartitionable) {
			return true;
		}
		return super.isResourcePartitionable(theResourceType);
	}

}
