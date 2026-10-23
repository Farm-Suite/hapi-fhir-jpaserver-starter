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

	private static final int GRANT_PAGE = 100;

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

	/**
	 * ¿Tiene el actor algún Consent que le conceda acceso a este tenant ahora? Es lo que decide si puede
	 * entrar a un tenant que no es el suyo (token de otro tenant o sin tenant). Busca por destinatario, una
	 * búsqueda por cada uno (su practitioner y su organización), con el parámetro propio {@code grantee-ref}
	 * (custom-search-parameters/consent-grantee-ref.json) que compara el texto de la referencia: el estándar
	 * {@code grantee} resuelve antes el recurso destino dentro de las particiones de la petición, y un
	 * Practitioner (DEFAULT) o una organización de otro tenant no están en la del tenant. La vigencia se
	 * comprueba en memoria. Falla cerrado.
	 */
	public boolean hasActiveGrant(String tenantId, ConsentMatcher.Actor actor, Instant now) {
		if (!hasActor(actor)) return false;
		try {
			RequestDetails details = tenantRequests.systemIn(tenantId);
			for (String grantee : granteesOf(actor)) {
				SearchParameterMap map = new SearchParameterMap();
				map.add("grantee-ref", new TokenParam(null, grantee));
				map.add("status", new TokenParam("active"));
				IBundleProvider results = daoRegistry.getResourceDao(Consent.class).search(map, details);
				for (int from = 0; ; from += GRANT_PAGE) {
					List<IBaseResource> page = results.getResources(from, from + GRANT_PAGE);
					if (page.isEmpty()) break;
					if (page.stream().anyMatch(c -> c instanceof Consent consent && ConsentMatcher.isActiveGrant(consent, actor, now))) return true;
				}
			}
			return false;
		} catch (RuntimeException e) {
			log.error("No se pudo comprobar si {} tiene un Consent en el tenant {}: se deniega", actor, tenantId, e);
			return false;
		}
	}

	private static List<String> granteesOf(ConsentMatcher.Actor actor) {
		List<String> grantees = new java.util.ArrayList<>();
		if (actor.practitionerId() != null && !actor.practitionerId().isBlank()) grantees.add("Practitioner/" + actor.practitionerId());
		if (actor.organizationId() != null && !actor.organizationId().isBlank()) grantees.add("Organization/" + actor.organizationId());
		return grantees;
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
