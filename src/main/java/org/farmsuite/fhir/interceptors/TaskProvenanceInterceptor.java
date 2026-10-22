package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.consent.PatientSubjectResolver;
import org.farmsuite.fhir.consent.SubjectKind;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Trazabilidad de las Task: cada cambio de {@code owner} o de {@code status} deja un
 * {@link Provenance} en la misma partición que la Task, con quién lo hizo, cuándo, qué cambió y
 * contra qué versión anterior. {@code _history} de la Task dice <i>qué</i> cambió; el Provenance
 * dice <i>quién y por qué actividad</i> (asignar, delegar, cambiar de estado) y es el recurso
 * que se consulta por paciente/tarea en una auditoría.
 *
 * <p>El actor sale siempre de la sesión autenticada (JWT), nunca de nada que mande el cliente.
 * Si no hay sesión de practitioner (cuenta de servicio) el Provenance se escribe igualmente, sin
 * actor humano.
 *
 * <p>Por defecto best-effort: un fallo al escribir el Provenance se registra como ERROR y no
 * revierte el cambio de la Task (misma política que el espejo — ver {@code TaskOwnerSyncInterceptor}).
 * Con {@code farmsuite.traceability.fail-closed=true} el fallo aborta la transacción: si no hay
 * traza, no hay cambio. **Solo para tareas de una persona** (RGPD); una tarea operativa o de un
 * animal o un árbol sigue siendo best-effort.
 */
@Slf4j
@Interceptor
@Component
public class TaskProvenanceInterceptor {

	public static final String ACTIVITY_SYSTEM = "https://farmsuite.org/fhir/CodeSystem/task-activity";
	public static final String ACTIVITY_CREATE = "create";
	public static final String ACTIVITY_ASSIGN = "assign";
	public static final String ACTIVITY_DELEGATE = "delegate";
	public static final String ACTIVITY_UNASSIGN = "unassign";
	public static final String ACTIVITY_STATUS_CHANGE = "status-change";

	private final DaoRegistry daoRegistry;
	private final UserSessionRepository userSessionRepository;
	private final PatientSubjectResolver patientSubjectResolver;
	private final boolean failClosed;

	public TaskProvenanceInterceptor(
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

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void onCreated(
		IBaseResource theResource,
		RequestDetails theRequestDetails,
		RequestPartitionId theRequestPartitionId
	) {
		if (!(theResource instanceof Task task)) return;
		record(null, task, activitiesOfCreation(task), theRequestDetails, theRequestPartitionId);
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
	public void onUpdated(
		IBaseResource theOldResource,
		IBaseResource theNewResource,
		RequestDetails theRequestDetails,
		RequestPartitionId theRequestPartitionId
	) {
		if (!(theOldResource instanceof Task oldTask) || !(theNewResource instanceof Task newTask)) return;
		record(oldTask, newTask, activitiesOf(oldTask, newTask), theRequestDetails, theRequestPartitionId);
	}

	private void record(
		Task oldTask,
		Task newTask,
		List<String> activities,
		RequestDetails theRequestDetails,
		RequestPartitionId theRequestPartitionId
	) {
		if (activities.isEmpty()) return;

		String tenantId = TaskOwnerSyncInterceptor.resolveTenantId(theRequestDetails, theRequestPartitionId);
		boolean mustFailClosed = failClosed && involvesPerson(newTask, tenantId, theRequestDetails);
		if (tenantId == null) {
			log.error("Provenance de Task {} no escrito: sin tenant resoluble", newTask.getIdElement().getIdPart());
			if (mustFailClosed) throw new InternalErrorException("No se pudo registrar la trazabilidad de la tarea: tenant no resoluble");
			return;
		}

		String actorPractitionerId = userSessionRepository.getUserSession().map(UserSessionModel::getPractitionerId).orElse(null);
		try {
			for (String activity : activities) {
				Provenance provenance = buildProvenance(oldTask, newTask, activity, actorPractitionerId, new Date());
				daoRegistry.getResourceDao(Provenance.class).create(provenance,
					new SystemRequestDetails().setRequestPartitionId(RequestPartitionId.fromPartitionName(tenantId)));
			}
		} catch (Exception e) {
			log.error("No se pudo escribir el Provenance de la Task {} (tenant={}, actividades={})",
				newTask.getIdElement().getIdPart(), tenantId, activities, e);
			// Fail-closed: sin traza no hay cambio — la excepción aborta la transacción de la Task.
			if (mustFailClosed) throw new InternalErrorException("No se pudo registrar la trazabilidad (Provenance) de la tarea; cambio rechazado", e);
		}
	}

	/**
	 * El fail-closed solo aplica a tareas de una persona (RGPD): las que tienen {@code for} apuntando a
	 * un Patient que es una persona, o cuya persona no se puede determinar. Una tarea operativa (sin
	 * {@code for}) y las de animales o árboles no.
	 */
	private boolean involvesPerson(Task task, String tenantId, RequestDetails requestDetails) {
		if (!task.hasFor() || !task.getFor().hasReference()) return false;
		if (tenantId == null || requestDetails == null) return true;
		return patientSubjectResolver.factsOf(task, tenantId, requestDetails)
			.map(facts -> facts.kind() == SubjectKind.PERSON).orElse(true);
	}

	/** Toda Task nueva deja un {@code create}; si nace ya asignada, además un {@code assign}. */
	static List<String> activitiesOfCreation(Task task) {
		List<String> activities = new ArrayList<>();
		activities.add(ACTIVITY_CREATE);
		if (ownerReference(task) != null) activities.add(ACTIVITY_ASSIGN);
		return activities;
	}

	/** Actividades que explican el cambio entre dos versiones de una Task; vacío si no cambió ni owner ni status. */
	static List<String> activitiesOf(Task oldTask, Task newTask) {
		List<String> activities = new ArrayList<>();

		String oldOwner = ownerReference(oldTask);
		String newOwner = ownerReference(newTask);
		if (!java.util.Objects.equals(oldOwner, newOwner)) {
			if (newOwner == null) {
				activities.add(ACTIVITY_UNASSIGN);
			} else if (oldOwner != null && oldOwner.startsWith("Organization/")
				&& (newOwner.startsWith("Practitioner/") || newOwner.startsWith("PractitionerRole/"))) {
				activities.add(ACTIVITY_DELEGATE);
			} else {
				activities.add(ACTIVITY_ASSIGN);
			}
		}

		if (oldTask.getStatus() != newTask.getStatus()) {
			activities.add(ACTIVITY_STATUS_CHANGE);
		}
		return activities;
	}

	static Provenance buildProvenance(Task oldTask, Task newTask, String activity, String actorPractitionerId, Date recorded) {
		Provenance provenance = new Provenance();
		provenance.addTarget(new Reference(targetOf(newTask)));
		provenance.setRecorded(recorded);
		provenance.setOccurred(new org.hl7.fhir.r5.model.DateTimeType(recorded));
		provenance.setActivity(new CodeableConcept().addCoding(new Coding().setSystem(ACTIVITY_SYSTEM).setCode(activity)));

		if (actorPractitionerId != null && !actorPractitionerId.isBlank()) {
			Provenance.ProvenanceAgentComponent agent = provenance.addAgent();
			agent.setType(new CodeableConcept().addCoding(new Coding()
				.setSystem("http://terminology.hl7.org/CodeSystem/provenance-participant-type").setCode("author")));
			agent.setWho(new Reference("Practitioner/" + actorPractitionerId));
		}

		// Qué cambió: la versión anterior de la Task, para poder reconstruir el antes/después.
		if (oldTask != null && oldTask.getIdElement().hasVersionIdPart()) {
			Provenance.ProvenanceEntityComponent entity = provenance.addEntity();
			entity.setRole(Provenance.ProvenanceEntityRole.REVISION);
			entity.setWhat(new Reference(oldTask.getIdElement().getValue()));
		}
		return provenance;
	}

	private static String targetOf(Task task) {
		return task.getIdElement().hasVersionIdPart()
			? task.getIdElement().getValue()
			: "Task/" + task.getIdElement().getIdPart();
	}

	private static String ownerReference(Task task) {
		return task.hasOwner() && task.getOwner().hasReference() ? task.getOwner().getReference() : null;
	}
}
