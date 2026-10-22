package org.farmsuite.fhir.consent;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Group;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Busca, en la partición del tenant, si hay un {@link Consent} que permita a una organización ver
 * los datos de un paciente. Un Consent puede concederse al paciente o a un {@link Group} de personas
 * al que pertenezca (así un consentimiento cubre a todo el grupo hacia una organización, en vez de
 * uno por paciente). Falla cerrado: si la búsqueda falla, no hay consentimiento.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsentFinder {

	private final DaoRegistry daoRegistry;
	private final TenantRequests tenantRequests;

	public boolean permits(String tenantId, String patientId, String actorOrganizationId, String resourceType, Instant now) {
		try {
			ReferenceOrListParam subjects = new ReferenceOrListParam().addOr(new ReferenceParam("Patient/" + patientId));
			for (String groupId : groupsOf(tenantId, patientId)) {
				subjects.addOr(new ReferenceParam("Group/" + groupId));
			}

			SearchParameterMap map = new SearchParameterMap();
			map.add("subject", subjects);
			map.add("grantee", new ReferenceParam("Organization/" + actorOrganizationId));
			map.add("status", new TokenParam("active"));

			RequestDetails details = tenantRequests.systemIn(tenantId);
			IBundleProvider results = daoRegistry.getResourceDao(Consent.class).search(map, details);
			List<IBaseResource> consents = results.getAllResources();
			return consents.stream().anyMatch(c -> c instanceof Consent consent
				&& ConsentMatcher.permits(consent, actorOrganizationId, resourceType, now));
		} catch (RuntimeException e) {
			log.error("No se pudo comprobar el Consent de Patient/{} para Organization/{} (tenant={}): se deniega",
				patientId, actorOrganizationId, tenantId, e);
			return false;
		}
	}

	private List<String> groupsOf(String tenantId, String patientId) {
		SearchParameterMap map = new SearchParameterMap();
		map.add("member", new ReferenceParam("Patient/" + patientId));
		IBundleProvider groups = daoRegistry.getResourceDao(Group.class).search(map, tenantRequests.systemIn(tenantId));
		return groups.getAllResources().stream().map(g -> g.getIdElement().getIdPart()).toList();
	}
}
