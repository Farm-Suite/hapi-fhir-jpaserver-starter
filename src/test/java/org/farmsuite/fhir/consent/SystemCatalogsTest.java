package org.farmsuite.fhir.consent;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.farmsuite.fhir.consent.CatalogProperties.TaskCapabilities;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Los catálogos de sistema se leen de la partición DEFAULT; sin catálogo o con código desconocido no se concede nada. */
@SuppressWarnings("unchecked")
class SystemCatalogsTest {

	private IFhirResourceDao<CodeSystem> dao;
	private SystemCatalogs catalogs;

	@BeforeEach
	void setUp() {
		DaoRegistry registry = mock(DaoRegistry.class);
		dao = mock(IFhirResourceDao.class);
		when(registry.getResourceDao(CodeSystem.class)).thenReturn(dao);
		catalogs = new SystemCatalogs(registry);
	}

	private static CodeSystem taskTypes() {
		CodeSystem cs = new CodeSystem();
		CodeSystem.ConceptDefinitionComponent c = cs.addConcept().setCode("delivery");
		c.addProperty().setCode("consent-read").setValue(new CodeType("Observation"));
		c.addProperty().setCode("consent-write").setValue(new CodeType("MedicationDispense"));
		return cs;
	}

	private static CodeSystem organizationTypes() {
		CodeSystem cs = new CodeSystem();
		cs.addConcept().setCode("pharmacy").addProperty().setCode("external-service").setValue(new BooleanType(true));
		cs.addConcept().setCode("farm").addProperty().setCode("external-service").setValue(new BooleanType(false));
		return cs;
	}

	@Test
	void taskCapabilities_readsFarmTaskTypeFromDefault() {
		when(dao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(taskTypes());

		TaskCapabilities caps = catalogs.taskCapabilities("delivery");

		assertThat(caps.write()).containsExactly("MedicationDispense");
		assertThat(caps.read()).containsExactlyInAnyOrder("Observation", "MedicationDispense");
		ArgumentCaptor<IdType> id = ArgumentCaptor.forClass(IdType.class);
		ArgumentCaptor<RequestDetails> details = ArgumentCaptor.forClass(RequestDetails.class);
		verify(dao).read(id.capture(), details.capture());
		assertThat(id.getValue().getIdPart()).isEqualTo("farm-task-type");
		assertThat(details.getValue()).isInstanceOf(SystemRequestDetails.class);
		assertThat(((SystemRequestDetails) details.getValue()).getRequestPartitionId().isDefaultPartition()).isTrue();
	}

	@Test
	void taskCapabilities_unknownOrNullCode_isNone() {
		when(dao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(taskTypes());

		assertThat(catalogs.taskCapabilities("unknown")).isEqualTo(TaskCapabilities.NONE);
		assertThat(catalogs.taskCapabilities(null)).isEqualTo(TaskCapabilities.NONE);
	}

	@Test
	void taskCapabilities_missingCatalog_isNoneAndRetriedNextTime() {
		when(dao.read(any(IdType.class), any(RequestDetails.class)))
			.thenThrow(new ResourceNotFoundException("no hay"))
			.thenReturn(taskTypes());

		assertThat(catalogs.taskCapabilities("delivery")).isEqualTo(TaskCapabilities.NONE);
		assertThat(catalogs.taskCapabilities("delivery").write()).containsExactly("MedicationDispense");
	}

	@Test
	void taskCapabilities_isCachedWhileFresh() {
		when(dao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(taskTypes());

		catalogs.taskCapabilities("delivery");
		catalogs.taskCapabilities("delivery");

		verify(dao, times(1)).read(any(IdType.class), any(RequestDetails.class));
	}

	@Test
	void isExternalServiceOrganizationType_readsOrganizationTypeFromDefault() {
		when(dao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(organizationTypes());

		assertThat(catalogs.isExternalServiceOrganizationType("pharmacy")).isTrue();
		assertThat(catalogs.isExternalServiceOrganizationType("farm")).isFalse();
		ArgumentCaptor<IdType> id = ArgumentCaptor.forClass(IdType.class);
		ArgumentCaptor<RequestDetails> details = ArgumentCaptor.forClass(RequestDetails.class);
		verify(dao).read(id.capture(), details.capture());
		assertThat(id.getValue().getIdPart()).isEqualTo("organization-type");
		assertThat(((SystemRequestDetails) details.getValue()).getRequestPartitionId().isDefaultPartition()).isTrue();
	}

	@Test
	void isExternalServiceOrganizationType_unknownCodeOrNull_isFalse() {
		when(dao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(organizationTypes());

		assertThat(catalogs.isExternalServiceOrganizationType("unknown")).isFalse();
		assertThat(catalogs.isExternalServiceOrganizationType(null)).isFalse();
	}

	@Test
	void isExternalServiceOrganizationType_missingCatalog_isFalse() {
		when(dao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new ResourceNotFoundException("no hay"));

		assertThat(catalogs.isExternalServiceOrganizationType("pharmacy")).isFalse();
	}
}
