package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.consent.TenantRequests;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Quién registró, revocó o modificó cada {@link Consent}, y cuándo. Para el RGPD no basta con que el
 * consentimiento exista: hay que poder demostrar <i>quién lo anotó</i> y <i>quién lo retiró</i>. El
 * {@code _history} del Consent dice qué cambió; este {@link Provenance} (en la misma partición) dice
 * quién y con qué actividad ({@code grant}, {@code revoke}, {@code modify}, {@code record}), citando la
 * versión anterior. El actor sale siempre de la sesión autenticada, nunca de lo que mande el cliente.
 *
 * <p>Un Consent es siempre sobre personas, así que {@code farmsuite.traceability.fail-closed=true}
 * aplica siempre: sin traza no hay consentimiento (ni revocación).
 */
@Slf4j
@Interceptor
@Component
public class ConsentProvenanceInterceptor {

	public static final String ACTIVITY_SYSTEM = "https://farmsuite.org/fhir/CodeSystem/consent-activity";
	public static final String ACTIVITY_GRANT = "grant";
	public static final String ACTIVITY_REVOKE = "revoke";
	public static final String ACTIVITY_MODIFY = "modify";
	public static final String ACTIVITY_RECORD = "record";

	private final DaoRegistry daoRegistry;
	private final TenantRequests tenantRequests;
	private final UserSessionRepository userSessionRepository;
	private final boolean failClosed;

	public ConsentProvenanceInterceptor(
		DaoRegistry daoRegistry,
		TenantRequests tenantRequests,
		UserSessionRepository userSessionRepository,
		@Value("${farmsuite.traceability.fail-closed:false}") boolean failClosed
	) {
		this.daoRegistry = daoRegistry;
		this.tenantRequests = tenantRequests;
		this.userSessionRepository = userSessionRepository;
		this.failClosed = failClosed;
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void onCreated(IBaseResource theResource, RequestDetails theRequestDetails, RequestPartitionId theRequestPartitionId) {
		if (!(theResource instanceof Consent consent)) return;
		record(null, consent, activitiesOfCreation(consent), theRequestDetails, theRequestPartitionId);
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
	public void onUpdated(IBaseResource theOld, IBaseResource theNew, RequestDetails theRequestDetails, RequestPartitionId theRequestPartitionId) {
		if (!(theOld instanceof Consent oldConsent) || !(theNew instanceof Consent newConsent)) return;
		record(oldConsent, newConsent, activitiesOf(oldConsent, newConsent), theRequestDetails, theRequestPartitionId);
	}

	private void record(Consent oldConsent, Consent newConsent, List<String> activities, RequestDetails requestDetails, RequestPartitionId partitionId) {
		if (activities.isEmpty()) return;

		String tenantId = TaskOwnerSyncInterceptor.resolveTenantId(requestDetails, partitionId);
		if (tenantId == null) {
			log.error("Provenance del Consent {} no escrito: sin tenant resoluble", newConsent.getIdElement().getIdPart());
			if (failClosed) throw new InternalErrorException("No se pudo registrar la trazabilidad (Provenance) del consentimiento: tenant no resoluble");
			return;
		}

		String actor = userSessionRepository.getUserSession().map(UserSessionModel::getPractitionerId).orElse(null);
		try {
			for (String activity : activities) {
				daoRegistry.getResourceDao(Provenance.class).create(
					buildProvenance(oldConsent, newConsent, activity, actor, new Date()), tenantRequests.systemIn(tenantId));
			}
		} catch (Exception e) {
			log.error("No se pudo escribir el Provenance del Consent {} (tenant={}, actividades={})",
				newConsent.getIdElement().getIdPart(), tenantId, activities, e);
			if (failClosed) throw new InternalErrorException("No se pudo registrar la trazabilidad (Provenance) del consentimiento; cambio rechazado", e);
		}
	}

	/** Un Consent nuevo: {@code grant} si ya nace activo, {@code record} si es un borrador u otro estado. */
	static List<String> activitiesOfCreation(Consent consent) {
		return List.of(consent.getStatus() == Consent.ConsentState.ACTIVE ? ACTIVITY_GRANT : ACTIVITY_RECORD);
	}

	/**
	 * Qué explica el cambio entre dos versiones: pasar a {@code inactive} es una {@code revoke}; volver a
	 * {@code active}, un {@code grant}; cualquier otro cambio de contenido (organización, periodo, tipos de
	 * dato…), un {@code modify}. Vacío si no cambió nada que importe (p. ej. solo metadatos).
	 */
	static List<String> activitiesOf(Consent oldConsent, Consent newConsent) {
		List<String> activities = new ArrayList<>();
		if (oldConsent.getStatus() != newConsent.getStatus()) {
			activities.add(switch (newConsent.getStatus()) {
				case INACTIVE -> ACTIVITY_REVOKE;
				case ACTIVE -> ACTIVITY_GRANT;
				default -> ACTIVITY_MODIFY;
			});
		}
		if (!sameContentIgnoringStatus(oldConsent, newConsent)) {
			activities.add(ACTIVITY_MODIFY);
		}
		return activities.stream().distinct().toList();
	}

	private static boolean sameContentIgnoringStatus(Consent oldConsent, Consent newConsent) {
		Consent a = oldConsent.copy();
		Consent b = newConsent.copy();
		for (Consent c : List.of(a, b)) {
			c.setStatus(Consent.ConsentState.ACTIVE);
			c.setMeta(null);
			c.setId((String) null);
		}
		return a.equalsDeep(b);
	}

	static Provenance buildProvenance(Consent oldConsent, Consent newConsent, String activity, String actorPractitionerId, Date recorded) {
		Provenance provenance = new Provenance();
		provenance.addTarget(new Reference(newConsent.getIdElement().hasVersionIdPart()
			? newConsent.getIdElement().getValue()
			: "Consent/" + newConsent.getIdElement().getIdPart()));
		provenance.setRecorded(recorded);
		provenance.setOccurred(new org.hl7.fhir.r5.model.DateTimeType(recorded));
		provenance.setActivity(new CodeableConcept().addCoding(new Coding().setSystem(ACTIVITY_SYSTEM).setCode(activity)));

		if (actorPractitionerId != null && !actorPractitionerId.isBlank()) {
			Provenance.ProvenanceAgentComponent agent = provenance.addAgent();
			agent.setType(new CodeableConcept().addCoding(new Coding()
				.setSystem("http://terminology.hl7.org/CodeSystem/provenance-participant-type").setCode("author")));
			agent.setWho(new Reference("Practitioner/" + actorPractitionerId));
		}
		if (oldConsent != null && oldConsent.getIdElement().hasVersionIdPart()) {
			provenance.addEntity()
				.setRole(Provenance.ProvenanceEntityRole.REVISION)
				.setWhat(new Reference(oldConsent.getIdElement().getValue()));
		}
		return provenance;
	}
}
