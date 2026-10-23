package org.farmsuite.fhir.consent;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentOutcome;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentContextServices;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentService;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.consent.ConsentMatcher.Access;
import org.farmsuite.fhir.consent.ConsentMatcher.Actor;
import org.farmsuite.fhir.consent.PersonAccessPolicy.Decision;
import org.farmsuite.fhir.interceptors.ClinicalAccessAuditInterceptor;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Aplica {@link PersonAccessPolicy} a cada recurso clínico que una petición va a devolver (HAPI
 * llama a {@link #canSeeResource} por recurso, también en búsquedas, donde {@code REJECT} lo quita
 * del resultado) y, con {@link #mayWrite}, a los que va a escribir. La organización de quien accede
 * es la de su sesión: tras un token exchange es la organización para la que se emitió el token, que
 * es justo lo que distingue a una farmacia externa de la organización que gestiona al paciente.
 *
 * <p>No se aplica a la cuenta de servicio ni a las lecturas internas del servidor.
 */
@Slf4j
@Component
public class FarmSuiteConsentService implements IConsentService {

	private final UserSessionRepository userSessionRepository;
	private final PatientSubjectResolver patientSubjectResolver;
	private final ConsentFinder consentFinder;
	private final DaoRegistry daoRegistry;
	private final TenantRequests tenantRequests;
	private final TenantAccessService tenantAccess;
	private final boolean requireOrganization;

	private static final String DENIED_KEY = "farmsuite.consent.denied";

	public FarmSuiteConsentService(
		UserSessionRepository userSessionRepository,
		PatientSubjectResolver patientSubjectResolver,
		ConsentFinder consentFinder,
		DaoRegistry daoRegistry,
		TenantRequests tenantRequests,
		TenantAccessService tenantAccess,
		// true: una sesión sin organización no accede a datos de personas (por defecto se permite,
		// porque el dueño del tenant puede no tener organización).
		@Value("${farmsuite.consent.require-organization:false}") boolean requireOrganization
	) {
		this.userSessionRepository = userSessionRepository;
		this.patientSubjectResolver = patientSubjectResolver;
		this.consentFinder = consentFinder;
		this.daoRegistry = daoRegistry;
		this.tenantRequests = tenantRequests;
		this.tenantAccess = tenantAccess;
		this.requireOrganization = requireOrganization;
	}

	@Override
	public ConsentOutcome startOperation(RequestDetails requestDetails, IConsentContextServices contextServices) {
		if (requestDetails instanceof SystemRequestDetails || userSessionRepository.isAdmin()) {
			return ConsentOutcome.AUTHORIZED;
		}
		return ConsentOutcome.PROCEED;
	}

	@Override
	public ConsentOutcome canSeeResource(RequestDetails requestDetails, IBaseResource resource, IConsentContextServices contextServices) {
		if (evaluate(requestDetails, resource, Access.READ) != Decision.DENY) return ConsentOutcome.PROCEED;
		deniedIn(requestDetails).add(resource.fhirType() + "/" + resource.getIdElement().getIdPart());
		return ConsentOutcome.REJECT;
	}

	/**
	 * ¿Puede quien hace la petición modificar este recurso? Misma regla que para leer: un recurso de
	 * una persona que gestiona otra organización solo se toca con Consent, y el Consent tiene que
	 * conceder escritura ({@code provision.action = write}); borrar no se concede nunca por Consent. No
	 * se aplica a la cuenta de servicio ni a las operaciones internas del servidor. Si deniega, deja el
	 * AuditEvent.
	 */
	public boolean mayWrite(RequestDetails requestDetails, IBaseResource resource, Access access) {
		if (requestDetails == null || requestDetails instanceof SystemRequestDetails || userSessionRepository.isAdmin()) return true;
		boolean denied = evaluate(requestDetails, resource, access) == Decision.DENY;
		if (denied) {
			deniedIn(requestDetails).add(resource.fhirType() + "/" + resource.getIdElement().getIdPart());
			auditDenied(requestDetails);
		}
		return !denied;
	}

	/**
	 * Crear un recurso. Como {@link #mayWrite}, salvo que un externo (entra por Consent) no crea tareas: las
	 * ve y las actualiza si son suyas, pero las crea quien pertenece al tenant. HAPI no distingue crear de
	 * actualizar en una regla de escritura, así que se corta aquí.
	 */
	public boolean mayCreate(RequestDetails requestDetails, IBaseResource resource) {
		boolean externalCreatingATask = requestDetails != null && !(requestDetails instanceof SystemRequestDetails)
			&& !userSessionRepository.isAdmin() && resource instanceof Task
			&& tenantAccess.resolve(requestDetails) == TenantAccessService.Kind.BY_CONSENT;
		if (externalCreatingATask) {
			deniedIn(requestDetails).add(resource.fhirType() + "/" + resource.getIdElement().getIdPart());
			auditDenied(requestDetails);
			return false;
		}
		return mayWrite(requestDetails, resource, Access.WRITE);
	}

	/** La decisión para un recurso; cualquier cosa que no sea DENY deja pasar (incluido lo que no cuelga de una persona). */
	private Decision evaluate(RequestDetails requestDetails, IBaseResource resource, Access access) {
		if (tenantAccess.resolve(requestDetails) == TenantAccessService.Kind.BY_CONSENT) {
			return evaluateExternal(requestDetails, resource, access);
		}
		if (!PatientSubjectResolver.PATIENT_LINKED_TYPES.contains(resource.fhirType())) return Decision.ALLOW_NOT_A_PERSON;

		String tenantId = requestDetails.getTenantId();
		if (tenantId == null || tenantId.isBlank()) return Decision.ALLOW_NOT_A_PERSON;

		Optional<PatientFacts> patient = patientSubjectResolver.factsOf(resource, tenantId, requestDetails);
		if (patient.isEmpty()) return Decision.ALLOW_NOT_A_PERSON; // sin paciente identificable no hay dato personal que proteger

		String actorOrganizationId = userSessionRepository.getUserSession().map(UserSessionModel::getOrganizationId).orElse(null);
		String actorPractitionerId = userSessionRepository.getUserSession().map(UserSessionModel::getPractitionerId).orElse(null);
		Decision decision = PersonAccessPolicy.decide(
			patient.get(),
			new Actor(actorPractitionerId, actorOrganizationId),
			resource.fhirType(),
			access,
			(organization, ancestor) -> patientSubjectResolver.isWithin(tenantId, organization, ancestor, requestDetails),
			(patientId, actor, type, mode, now) -> permitsCached(tenantId, patientId, actor, type, mode, now, requestDetails),
			Instant.now(),
			requireOrganization);

		if (decision == Decision.DENY) {
			log.warn("Acceso DENEGADO: organization={} practitioner={} -> {}/{} (paciente {}{}, tenant {})",
				actorOrganizationId, userSessionRepository.getUserSession().map(UserSessionModel::getPractitionerId).orElse(null),
				resource.fhirType(), resource.getIdElement().getIdPart(), patient.get().patientId(),
				patient.get().unresolved() ? ", sin poder leerlo" : "", tenantId);
		} else if (decision == Decision.ALLOW_WITHOUT_ORGANIZATION_CONTEXT) {
			log.warn("Acceso a datos de una persona sin poder clasificarlo (sin organización en la sesión o paciente sin managingOrganization): {}/{}",
				resource.fhirType(), resource.getIdElement().getIdPart());
		}
		return decision;
	}

	/**
	 * Quien entra a este tenant por un Consent (no es de él): solo ve lo de pacientes que un Consent cubre
	 * —con el tipo y el modo pedidos— y las tareas que tiene asignadas. Un recurso de paciente sin paciente
	 * identificable se deniega: no hay relación asistencial ni "dueño del tenant" a quien dejar pasar por el
	 * hueco de la sesión sin organización. Lo que no cuelga de un paciente (la terminología del tenant, los
	 * datos de sistema) no se decide aquí: ya lo dejaron pasar o no las reglas de {@code FarmSuiteRules}, que
	 * para quien entra por Consent no abren nada más.
	 */
	private Decision evaluateExternal(RequestDetails requestDetails, IBaseResource resource, Access access) {
		String type = resource.fhirType();
		if (!PatientSubjectResolver.PATIENT_LINKED_TYPES.contains(type)) return Decision.ALLOW_NOT_A_PERSON;

		String tenantId = requestDetails.getTenantId();
		var session = userSessionRepository.getUserSession();
		Actor actor = new Actor(
			session.map(UserSessionModel::getPractitionerId).orElse(null),
			session.map(UserSessionModel::getOrganizationId).orElse(null));

		if (resource instanceof Task task) {
			boolean mine = access != Access.DELETE
				&& TaskOwnership.ownedBy(task, actor, role -> practitionerOfRole(tenantId, role, requestDetails));
			return mine ? Decision.ALLOW_CONSENT : Decision.DENY;
		}

		Optional<PatientFacts> patient = patientSubjectResolver.factsOf(resource, tenantId, requestDetails);
		if (patient.isEmpty()) return Decision.DENY;
		Decision decision = PersonAccessPolicy.decideForExternal(
			patient.get(), actor, type, access,
			(patientId, who, resourceType, mode, now) -> permitsCached(tenantId, patientId, who, resourceType, mode, now, requestDetails),
			Instant.now());
		if (decision == Decision.DENY) {
			log.warn("Acceso DENEGADO (externo por Consent): practitioner={} organization={} -> {}/{} (paciente {}, tenant {}, {})",
				actor.practitionerId(), actor.organizationId(), type, resource.getIdElement().getIdPart(),
				patient.get().patientId(), tenantId, access);
		}
		return decision;
	}

	/** El profesional de un PractitionerRole del tenant, o {@code null} si no existe o no se puede leer. */
	private String practitionerOfRole(String tenantId, String roleId, RequestDetails requestDetails) {
		String key = "farmsuite.role-practitioner." + tenantId + "/" + roleId;
		Object cached = requestDetails.getUserData().get(key);
		if (cached != null) return cached instanceof String id ? id : null;
		String practitionerId = null;
		try {
			PractitionerRole role = daoRegistry.getResourceDao(PractitionerRole.class)
				.read(new IdType("PractitionerRole", roleId), tenantRequests.systemIn(tenantId));
			if (role.hasPractitioner() && role.getPractitioner().hasReference()) {
				practitionerId = role.getPractitioner().getReferenceElement().getIdPart();
			}
		} catch (RuntimeException e) {
			log.warn("No se pudo leer PractitionerRole/{} del tenant {} para saber de quién es una tarea", roleId, tenantId, e);
		}
		requestDetails.getUserData().put(key, practitionerId == null ? new Object() : practitionerId);
		return practitionerId;
	}

	private boolean permitsCached(String tenantId, String patientId, Actor actor, String type, Access access, Instant now, RequestDetails requestDetails) {
		String key = "farmsuite.consent." + tenantId + "/" + patientId + "/" + actor.organizationId() + "/" + actor.practitionerId() + "/" + type + "/" + access;
		Object cached = requestDetails.getUserData().get(key);
		if (cached instanceof Boolean allowed) return allowed;
		boolean allowed = consentFinder.permits(tenantId, patientId, actor, type, access, now);
		requestDetails.getUserData().put(key, allowed);
		return allowed;
	}

	@Override
	public void completeOperationSuccess(RequestDetails requestDetails, IConsentContextServices contextServices) {
		auditDenied(requestDetails);
	}

	@Override
	public void completeOperationFailure(RequestDetails requestDetails, BaseServerResponseException exception, IConsentContextServices contextServices) {
		auditDenied(requestDetails);
	}

	/** Recursos denegados en esta petición, acumulados para escribir un único AuditEvent al terminar. */
	@SuppressWarnings("unchecked")
	private List<String> deniedIn(RequestDetails requestDetails) {
		return (List<String>) requestDetails.getUserData().computeIfAbsent(DENIED_KEY, k -> new ArrayList<String>());
	}

	/**
	 * Una petición que deniega mil recursos deja <b>un</b> AuditEvent, no mil. Best-effort a
	 * propósito: el acceso ya está denegado, un fallo al auditarlo no debe convertir la respuesta en
	 * un error ni ocultar la denegación.
	 */
	private void auditDenied(RequestDetails requestDetails) {
		Object denied = requestDetails.getUserData().remove(DENIED_KEY);
		if (!(denied instanceof List<?> references) || references.isEmpty()) return;

		String tenantId = requestDetails.getTenantId();
		var session = userSessionRepository.getUserSession();
		try {
			AuditEvent event = ClinicalAccessAuditInterceptor.buildDeniedAuditEvent(
				references.stream().map(String::valueOf).toList(),
				session.map(UserSessionModel::getPractitionerId).orElse(null),
				session.map(UserSessionModel::getOrganizationId).orElse(null),
				new Date());
			daoRegistry.getResourceDao(AuditEvent.class).create(event, tenantRequests.systemIn(tenantId));
		} catch (RuntimeException e) {
			log.error("No se pudo auditar el acceso denegado ({} recursos, tenant={})", references.size(), tenantId, e);
		}
	}
}
