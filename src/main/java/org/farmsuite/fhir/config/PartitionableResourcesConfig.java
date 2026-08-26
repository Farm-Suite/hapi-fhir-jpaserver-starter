package org.farmsuite.fhir.config;

import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.partition.RequestPartitionHelperSvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Set;

@Configuration
public class PartitionableResourcesConfig {

	// Tipos que HAPI marca como no-particionables por defecto (ver
	// BaseRequestPartitionHelperSvc.NON_PARTITIONABLE_RESOURCE_NAMES) pero que
	// FarmSuite necesita aislados por tenant.
	private static final Set<String> FORCE_PARTITIONABLE = Set.of(
		"CodeSystem",
		"ConceptMap",
		"StructureDefinition",
		"ValueSet"
	);

	@Bean
	@Primary
	public IRequestPartitionHelperSvc requestPartitionHelperService() {
		return new RequestPartitionHelperSvc() {
			@Override
			public boolean isResourcePartitionable(String theResourceType) {
				if (FORCE_PARTITIONABLE.contains(theResourceType)) {
					return true;
				}
				return super.isResourcePartitionable(theResourceType);
			}
		};
	}
}
