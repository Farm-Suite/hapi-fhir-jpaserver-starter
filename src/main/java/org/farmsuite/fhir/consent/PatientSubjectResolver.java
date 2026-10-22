package org.farmsuite.fhir.consent;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Averigua de qué paciente es un recurso clínico y qué es ese paciente (persona, animal o planta,
 * y qué organización lo gestiona), y la jerarquía de organizaciones, leyendo en la partición del
 * tenant. Es lo que necesitan el control de acceso por {@code Consent} y el fail-closed de auditoría,
 * que solo aplican a personas.
 *
 * <p>Las lecturas se cachean <b>por petición</b> ({@code RequestDetails.getUserData()}): una
 * búsqueda de mil Observation del mismo paciente lee ese Patient una sola vez.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientSubjectResolver {

	/** Recursos que cuelgan de un paciente y cuyo acceso se controla y audita. */
	public static final Set<String> PATIENT_LINKED_TYPES = Set.of(
		"Patient", "Observation", "Encounter", "EpisodeOfCare", "Procedure", "Immunization",
		"MedicationRequest", "MedicationDispense", "MedicationAdministration", "ServiceRequest", "CarePlan", "Task");

	private static final String[] PATIENT_PATHS = {"subject", "patient", "for"};
	private static final int MAX_HIERARCHY_DEPTH = 10;

	private final DaoRegistry daoRegistry;
	private final FhirContext fhirContext;
	private final TenantRequests tenantRequests;

	/** Qué es el paciente del recurso; vacío si el recurso no tiene paciente o no se puede leer. */
	public Optional<PatientFacts> factsOf(IBaseResource resource, String tenantId, RequestDetails requestDetails) {
		return patientIdOf(resource).flatMap(patientId -> factsOfPatient(patientId, resource, tenantId, requestDetails));
	}

	private Optional<PatientFacts> factsOfPatient(String patientId, IBaseResource source, String tenantId, RequestDetails requestDetails) {
		if (source instanceof Patient patient) {
			return Optional.of(toFacts(patientId, patient));
		}
		Map<Object, Object> cache = requestDetails.getUserData();
		String key = "farmsuite.patient." + tenantId + "/" + patientId;
		Object cached = cache.get(key);
		if (cached instanceof PatientFacts facts) return Optional.of(facts);
		if (cached == MISSING) return Optional.empty();

		try {
			Patient patient = daoRegistry.getResourceDao(Patient.class).read(new IdType("Patient", patientId), tenantRequests.systemIn(tenantId));
			PatientFacts facts = toFacts(patientId, patient);
			cache.put(key, facts);
			return Optional.of(facts);
		} catch (RuntimeException e) {
			log.warn("No se pudo leer Patient/{} del tenant {} para clasificar el acceso", patientId, tenantId, e);
			cache.put(key, MISSING);
			return Optional.empty();
		}
	}

	/** ¿{@code organizationId} es {@code ancestorId} o cuelga de ella por {@code Organization.partOf}? */
	public boolean isWithin(String tenantId, String organizationId, String ancestorId, RequestDetails requestDetails) {
		String current = organizationId;
		for (int depth = 0; current != null && depth < MAX_HIERARCHY_DEPTH; depth++) {
			if (current.equals(ancestorId)) return true;
			current = parentOf(tenantId, current, requestDetails);
		}
		return false;
	}

	private String parentOf(String tenantId, String organizationId, RequestDetails requestDetails) {
		Map<Object, Object> cache = requestDetails.getUserData();
		String key = "farmsuite.org-parent." + tenantId + "/" + organizationId;
		if (cache.containsKey(key)) {
			Object cached = cache.get(key);
			return cached == MISSING ? null : (String) cached;
		}
		String parent = null;
		try {
			Organization organization = daoRegistry.getResourceDao(Organization.class)
				.read(new IdType("Organization", organizationId), tenantRequests.systemIn(tenantId));
			if (organization.hasPartOf() && organization.getPartOf().hasReference()) {
				parent = organization.getPartOf().getReferenceElement().getIdPart();
			}
		} catch (RuntimeException e) {
			log.warn("No se pudo leer Organization/{} del tenant {} para recorrer la jerarquía", organizationId, tenantId, e);
		}
		cache.put(key, parent == null ? MISSING : parent);
		return parent;
	}

	private Optional<String> patientIdOf(IBaseResource resource) {
		if (resource instanceof Patient) {
			return resource.getIdElement().hasIdPart() ? Optional.of(resource.getIdElement().getIdPart()) : Optional.empty();
		}
		var terser = fhirContext.newTerser();
		for (String path : PATIENT_PATHS) {
			try {
				for (IBase value : terser.getValues(resource, path)) {
					if (value instanceof Reference reference && reference.hasReference()
						&& "Patient".equals(reference.getReferenceElement().getResourceType())
						&& reference.getReferenceElement().hasIdPart()) {
						return Optional.of(reference.getReferenceElement().getIdPart());
					}
				}
			} catch (RuntimeException e) {
				// este tipo de recurso no tiene ese elemento: se prueba el siguiente
			}
		}
		return Optional.empty();
	}

	private static PatientFacts toFacts(String patientId, Patient patient) {
		String managing = patient.hasManagingOrganization() && patient.getManagingOrganization().hasReference()
			? patient.getManagingOrganization().getReferenceElement().getIdPart()
			: null;
		return new PatientFacts(patientId, SubjectKind.of(patient), managing);
	}

	private static final Object MISSING = new Object();
}
