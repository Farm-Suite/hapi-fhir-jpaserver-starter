package org.farmsuite.fhir.config;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.model.JobInstanceStartRequest;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Instalación de los JSON de {@code custom-search-parameters/} al arrancar. */
class CustomSearchParameterInitializerTest {

	private static final Path JSON_DIR = Path.of("src/main/resources/custom-search-parameters");

	@SuppressWarnings("unchecked")
	private final IFhirResourceDao<IBaseResource> dao = mock(IFhirResourceDao.class);
	private final IJobCoordinator jobCoordinator = mock(IJobCoordinator.class);
	private CustomSearchParameterInitializer initializer;

	@BeforeEach
	void setUp() {
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		when(daoRegistry.getResourceDao(any(IBaseResource.class))).thenReturn(dao);
		initializer = new CustomSearchParameterInitializer(
				FhirContext.forR5Cached(), daoRegistry, jobCoordinator, mock(ISearchParamRegistry.class));
	}

	private static int jsonCount() throws IOException {
		try (Stream<Path> files = Files.list(JSON_DIR)) {
			return (int) files.filter(p -> p.toString().endsWith(".json")).count();
		}
	}

	private static DaoMethodOutcome outcome(boolean created, boolean nop) {
		DaoMethodOutcome outcome = new DaoMethodOutcome();
		outcome.setId(new IdType("SearchParameter/x"));
		outcome.setCreated(created);
		outcome.setNop(nop);
		return outcome;
	}

	private void daoReturns(DaoMethodOutcome outcome) {
		when(dao.update(any(IBaseResource.class), anyString(), any(RequestDetails.class))).thenReturn(outcome);
	}

	@Test
	void upsertsEveryJsonByItsUrl() throws Exception {
		daoReturns(outcome(true, false));

		initializer.run(new DefaultApplicationArguments());

		ArgumentCaptor<String> matchUrls = ArgumentCaptor.forClass(String.class);
		verify(dao, times(jsonCount())).update(any(IBaseResource.class), matchUrls.capture(), any(RequestDetails.class));
		List<String> urls = matchUrls.getAllValues();
		assertThat(urls).hasSize(jsonCount()).allMatch(u -> u.startsWith("url=")).doesNotHaveDuplicates();
	}

	@Test
	void launchesAReindexWhenResourcesWereCreated() throws Exception {
		daoReturns(outcome(true, false));

		initializer.run(new DefaultApplicationArguments());

		verify(jobCoordinator, atLeastOnce()).startInstance(any(RequestDetails.class), any(JobInstanceStartRequest.class));
	}

	@Test
	void launchesAReindexWhenResourcesWereUpdated() throws Exception {
		daoReturns(outcome(false, false));

		initializer.run(new DefaultApplicationArguments());

		verify(jobCoordinator, atLeastOnce()).startInstance(any(RequestDetails.class), any(JobInstanceStartRequest.class));
	}

	@Test
	void doesNotReindexWhenEverythingIsUpToDate() throws Exception {
		daoReturns(outcome(false, true));

		initializer.run(new DefaultApplicationArguments());

		verify(jobCoordinator, never()).startInstance(any(RequestDetails.class), any(JobInstanceStartRequest.class));
	}
}
