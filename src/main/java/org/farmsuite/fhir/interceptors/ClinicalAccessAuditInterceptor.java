package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.api.server.IPreResourceShowDetails;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.consent.PatientSubjectResolver;
import org.farmsuite.fhir.consent.SubjectKind;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Registra un {@link AuditEvent} por cada lectura o búsqueda de datos clínicos de un paciente
 * (quién, cuándo, qué recursos) en la partición del tenant. Es la parte "quién vio qué" de la
 * trazabilidad clínica; la parte "quién cambió qué" la cubre {@code TaskProvenanceInterceptor}
 * para las tareas y el propio {@code _history} de FHIR para el resto.
 *
 * <p>Solo se auditan las peticiones externas (autenticadas): las lecturas internas del servidor
 * ({@link SystemRequestDetails}, p. ej. resolver la organización de una Task) no son un acceso de
 * una persona. Un AuditEvent por petición, con una entity por recurso devuelto (acotado a
 * {@link #MAX_ENTITIES}; el total real queda en {@code outcome.text}).
 *
 * <p>Por defecto best-effort: si no se puede escribir se registra como ERROR y la lectura continúa
 * (bloquear la lectura clínica por un fallo de auditoría pone en riesgo la operación). Con
 * {@code farmsuite.traceability.fail-closed=true} la lectura se rechaza: un acceso que no se puede
 * auditar no se concede. **Solo para datos de personas** (RGPD): una lectura de un animal o un árbol
 * sigue siendo best-effort aunque esté activado.
 */
@Slf4j
@Interceptor
@Component
public class ClinicalAccessAuditInterceptor {

	static final int MAX_ENTITIES = 100;

	/** Rol del agente que es la organización en cuyo nombre actuó el practitioner. */
	public static final String ON_BEHALF_OF_ROLE = "on-behalf-of";

	/** Tipos con datos clínicos de un paciente cuyo acceso se audita. */
	static final Set<String> AUDITED_TYPES = Set.of(
		"Patient", "Observation", "Encounter", "EpisodeOfCare", "Procedure", "Immunization",
		"MedicationRequest", "MedicationDispense", "MedicationAdministration", "ServiceRequest", "CarePlan");

	private final DaoRegistry daoRegistry;
	private final UserSessionRepository userSessionRepository;
	private final PatientSubjectResolver patientSubjectResolver;
	private final boolean failClosed;

	public ClinicalAccessAuditInterceptor(
		DaoRegistry daoRegistry,
		UserSessionRepository userSessionRepository,
		PatientSubjectResolver patientSubjectResolver,
		@Value("${farmsuite.traceability.fail-closed:false}") boolean failClosed
	) {
		this.daoRegistry = daoRegistry;
		this.userSessionRepository = userSessionRepository;
		this.patientSubjectResolver = patientSubjectResolver;
		this.failClosed = failClosed;
	}

	@Hook(Pointcut.STORAGE_PRESHOW_RESOURCES)
	public void onShow(IPreResourceShowDetails theDetails, RequestDetails theRequestDetails) {
		if (theRequestDetails == null || theRequestDetails instanceof SystemRequestDetails) return;

		String tenantId = theRequestDetails.getTenantId();
		boolean tenantKnown = tenantId != null && !tenantId.isBlank();

		List<String> references = new ArrayList<>();
		// El RGPD solo obliga con personas: el fail-closed aplica si alguno de los recursos es de una
		// persona. Si no se puede saber de quién es, se trata como persona (lo prudente).
		boolean involvesPerson = false;
		for (int i = 0; i < theDetails.size(); i++) {
			IBaseResource resource = theDetails.getResource(i);
			if (resource != null && AUDITED_TYPES.contains(resource.fhirType()) && resource.getIdElement().hasIdPart()) {
				references.add(resource.fhirType() + "/" + resource.getIdElement().getIdPart());
				involvesPerson = involvesPerson || !tenantKnown
					|| patientSubjectResolver.factsOf(resource, tenantId, theRequestDetails)
						.map(facts -> facts.kind() == SubjectKind.PERSON).orElse(true);
			}
		}
		if (references.isEmpty()) return;
		boolean mustFailClosed = failClosed && involvesPerson;

		if (!tenantKnown) {
			log.error("AuditEvent de acceso clínico no escrito: sin tenant en la petición ({} recursos)", references.size());
			if (mustFailClosed) throw new InternalErrorException("No se pudo auditar el acceso clínico: tenant no resoluble");
			return;
		}

		String actor = userSessionRepository.getUserSession().map(UserSessionModel::getPractitionerId).orElse(null);
		try {
			AuditEvent event = buildAuditEvent(references, actor, new Date());
			daoRegistry.getResourceDao(AuditEvent.class).create(event,
				new SystemRequestDetails().setRequestPartitionId(RequestPartitionId.fromPartitionName(tenantId)));
		} catch (Exception e) {
			log.error("No se pudo escribir el AuditEvent de acceso clínico (tenant={}, recursos={})", tenantId, references.size(), e);
			// Fail-closed: un acceso que no se puede auditar no se concede.
			if (mustFailClosed) throw new InternalErrorException("No se pudo auditar el acceso clínico; lectura rechazada", e);
		}
	}

	static AuditEvent buildAuditEvent(List<String> resourceReferences, String actorPractitionerId, Date recorded) {
		AuditEvent event = new AuditEvent();
		event.addCategory(new CodeableConcept().addCoding(new Coding()
			.setSystem("http://terminology.hl7.org/CodeSystem/audit-event-type").setCode("rest").setDisplay("Restful Operation")));
		event.setCode(new CodeableConcept().addCoding(new Coding()
			.setSystem("http://hl7.org/fhir/restful-interaction").setCode(resourceReferences.size() > 1 ? "search" : "read")));
		event.setAction(AuditEvent.AuditEventAction.R);
		event.setSeverity(AuditEvent.AuditEventSeverity.INFORMATIONAL);
		event.setRecorded(recorded);
		event.setOutcome(new AuditEvent.AuditEventOutcomeComponent()
			.setCode(new Coding().setSystem("http://terminology.hl7.org/CodeSystem/audit-event-outcome").setCode("0"))
			.setDetail(List.of(new CodeableConcept().setText(resourceReferences.size() + " recursos devueltos"))));

		AuditEvent.AuditEventAgentComponent agent = event.addAgent();
		agent.setRequestor(true);
		agent.setType(new CodeableConcept().addCoding(new Coding()
			.setSystem("http://terminology.hl7.org/CodeSystem/provenance-participant-type").setCode("author")));
		if (actorPractitionerId != null && !actorPractitionerId.isBlank()) {
			agent.setWho(new Reference("Practitioner/" + actorPractitionerId));
		} else {
			agent.setWho(new Reference().setDisplay("sin sesión de practitioner (cuenta de servicio)"));
		}

		event.getSource().setObserver(new Reference().setDisplay("FarmSuite FHIR server"));

		resourceReferences.stream().limit(MAX_ENTITIES)
			.forEach(ref -> event.addEntity().setWhat(new Reference(ref)));
		return event;
	}

	/**
	 * Intento de acceso <b>denegado</b> por falta de Consent: mismo evento que un acceso, pero con
	 * resultado de fallo menor ({@code outcome.code = 4}), la organización en cuyo nombre se intentó
	 * (un segundo agente con rol {@link #ON_BEHALF_OF_ROLE}) y el motivo. Para el RGPD interesa poder demostrar también los
	 * intentos rechazados, no solo los accesos concedidos.
	 */
	public static AuditEvent buildDeniedAuditEvent(List<String> resourceReferences, String actorPractitionerId, String actorOrganizationId, Date recorded) {
		AuditEvent event = buildAuditEvent(resourceReferences, actorPractitionerId, recorded);
		event.setSeverity(AuditEvent.AuditEventSeverity.WARNING);
		event.getOutcome().getCode().setCode("4").setDisplay("Minor failure");
		event.getOutcome().setDetail(List.of(new CodeableConcept().setText(
			"Acceso denegado: la organización no es la gestora del paciente y no tiene un Consent activo (" + resourceReferences.size() + " recursos)")));
		if (actorOrganizationId != null && !actorOrganizationId.isBlank()) {
			// R5 no tiene agent.onBehalfOf: la organización en cuyo nombre se actuó va como segundo agente.
			event.addAgent()
				.setWho(new Reference("Organization/" + actorOrganizationId))
				.setRequestor(false)
				.addRole(new CodeableConcept().setText(ON_BEHALF_OF_ROLE));
		}
		return event;
	}
}
