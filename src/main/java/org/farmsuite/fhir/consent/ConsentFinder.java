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
 * Busca, en la partición del tenant, si hay un {@link Consent} que permita a un actor (practitioner u
 * organización) ver o escribir los datos de un paciente. Un Consent puede concederse al paciente o a un {@link Group} de personas
 * al que pertenezca (así un consentimiento cubre a todo el grupo hacia una organización, en vez de
 * uno por paciente). Falla cerrado: si la búsqueda falla, no hay consentimiento.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsentFinder {

	private final DaoRegistry daoRegistry;
	private final TenantRequests tenantRequests;

	public boolean permits(String tenantId, String patientId, ConsentMatcher.Actor actor, String resourceType,
			ConsentMatcher.Access access, Instant now) {
		if (!hasActor(actor)) return false;
		try {
			ReferenceOrListParam subjects = new ReferenceOrListParam().addOr(new ReferenceParam("Patient/" + patientId));
			for (String groupId : groupsOf(tenantId, patientId)) {
				subjects.addOr(new ReferenceParam("Group/" + groupId));
			}

			SearchParameterMap map = new SearchParameterMap();
			map.add("subject", subjects);
			// Sin filtro por grantee a propósito: el destinatario puede ser un Practitioner (partición DEFAULT) y
			// la búsqueda por referencia entre particiones no lo encuentra. Basta con acotar por sujeto, que da
			// pocos Consents, y ConsentMatcher comprueba el destinatario en memoria.
			map.add("status", new TokenParam("active"));

			RequestDetails details = tenantRequests.systemIn(tenantId);
			IBundleProvider results = daoRegistry.getResourceDao(Consent.class).search(map, details);
			List<IBaseResource> consents = results.getAllResources();
			return consents.stream().anyMatch(c -> c instanceof Consent consent
				&& ConsentMatcher.permits(consent, actor, resourceType, access, now));
		} catch (RuntimeException e) {
			log.error("No se pudo comprobar el Consent de Patient/{} para {} (tenant={}): se deniega",
				patientId, actor, tenantId, e);
			return false;
		}
	}

	/** Sin practitioner ni organización no hay a quién conceder nada. */
	private static boolean hasActor(ConsentMatcher.Actor actor) {
		return (actor.organizationId() != null && !actor.organizationId().isBlank())
			|| (actor.practitionerId() != null && !actor.practitionerId().isBlank());
	}

	private List<String> groupsOf(String tenantId, String patientId) {
		SearchParameterMap map = new SearchParameterMap();
		map.add("member", new ReferenceParam("Patient/" + patientId));
		IBundleProvider groups = daoRegistry.getResourceDao(Group.class).search(map, tenantRequests.systemIn(tenantId));
		return groups.getAllResources().stream().map(g -> g.getIdElement().getIdPart()).toList();
	}
}
