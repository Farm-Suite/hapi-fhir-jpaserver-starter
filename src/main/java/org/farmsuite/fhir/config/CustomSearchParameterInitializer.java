package org.farmsuite.fhir.config;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.jobs.parameters.PartitionedUrl;
import ca.uhn.fhir.batch2.jobs.reindex.ReindexJobParameters;
import ca.uhn.fhir.batch2.model.JobInstanceStartRequest;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.Enumeration;
import org.hl7.fhir.r5.model.Enumerations.VersionIndependentResourceTypesAll;
import org.hl7.fhir.r5.model.SearchParameter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

import static ca.uhn.fhir.batch2.jobs.reindex.ReindexUtils.JOB_REINDEX;

/**
 * Carga los recursos de conformance (SearchParameter, etc.) definidos como JSON en
 * classpath:custom-search-parameters/*.json y los sincroniza en el servidor (upsert
 * idempotente por su propio "url") al arrancar. Ver "Search Parameters custom" en CLAUDE.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomSearchParameterInitializer implements ApplicationRunner {

    private static final String RESOURCES_LOCATION_PATTERN = "classpath*:custom-search-parameters/*.json";

    private final FhirContext fhirContext;
    private final DaoRegistry daoRegistry;
    private final IJobCoordinator jobCoordinator;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        final Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(RESOURCES_LOCATION_PATTERN);

        for (Resource resource : resources) {
            upsert(resource);
        }
    }

    private void upsert(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            final IBaseResource parsed = fhirContext.newJsonParser().parseResource(inputStream);

            if (!(parsed instanceof CanonicalResource canonicalResource) || canonicalResource.getUrl() == null) {
                log.warn("Omitiendo {}: no es un CanonicalResource con 'url'", resource.getFilename());
                return;
            }

            // El SearchParameter en si mismo no es particionable: se guarda sin forzar partición
            // y sin disparar el reindex automático (que si necesita partición para buscar los
            // recursos afectados, p. ej. Permission). El reindex se dispara aparte, explicito.
            final SystemRequestDetails requestDetails = new SystemRequestDetails();
            requestDetails.getUserData().put(JpaConstants.SKIP_REINDEX_ON_UPDATE, Boolean.TRUE);

            final IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(parsed);
            final DaoMethodOutcome outcome = dao.update(
                    parsed,
                    "url=" + canonicalResource.getUrl(),
                    requestDetails
            );

            log.info(
                    "{} ({}) {} (id={})",
                    parsed.fhirType(),
                    canonicalResource.getUrl(),
                    outcome.isNop() ? "ya estaba al dia" : "creado/actualizado",
                    outcome.getId()
            );

            if (parsed instanceof SearchParameter searchParameter) {
                reindexAffectedResources(searchParameter);
            }
        } catch (IOException e) {
            log.error("Error cargando custom search parameter desde {}", resource.getFilename(), e);
        }
    }

    private void reindexAffectedResources(SearchParameter searchParameter) {
        final ReindexJobParameters params = new ReindexJobParameters();

        for (Enumeration<VersionIndependentResourceTypesAll> base : searchParameter.getBase()) {
            final PartitionedUrl partitionedUrl = new PartitionedUrl()
                    .setUrl(base.getValue().toCode() + "?")
                    .setRequestPartitionId(RequestPartitionId.allPartitions());
            params.addPartitionedUrl(partitionedUrl);
        }

        final JobInstanceStartRequest startRequest = new JobInstanceStartRequest();
        startRequest.setJobDefinitionId(JOB_REINDEX);
        startRequest.setParameters(params);

        jobCoordinator.startInstance(SystemRequestDetails.forAllPartitions(), startRequest);

        log.info("Reindex lanzado para base={} tras registrar SearchParameter {}",
                searchParameter.getBase().stream().map(b -> b.getValue().toCode()).toList(),
                searchParameter.getUrl());
    }
}
