package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.consent.CatalogProperties;
import org.farmsuite.fhir.consent.ConsentFinder;
import org.farmsuite.fhir.consent.ConsentMatcher.Actor;
import org.farmsuite.fhir.consent.ExternalAssignmentPolicy;
import org.farmsuite.fhir.consent.ExternalAssignmentPolicy.Requirement;
import org.farmsuite.fhir.consent.SystemCatalogs;
import org.farmsuite.fhir.consent.TenantRequests;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Asignar una tarea a alguien de fuera del tenant (un médico autónomo, una farmacia) exige antes el Consent que
 * le deja hacerla; si no, se rechaza (422) con lo que falta. Sin esto la tarea se crearía y el responsable la
 * vería, pero no podría abrir al paciente ni registrar nada, y fallaría después con un 403 genérico.
 *
 * <ul>
 *   <li><b>Externo</b>: un {@code Practitioner} sin ningún {@code PractitionerRole} en el tenant, o una
 *       {@code Organization} que no está en la partición del tenant. Los miembros (un {@code PractitionerRole}) y
 *       las organizaciones del tenant no pasan por aquí.</li>
 *   <li>Una organización externa tiene que ser de un tipo que preste servicios externos
 *       ({@code organization-type}, propiedad {@code external-service}).</li>
 *   <li>Con paciente ({@code Task.for}): el Consent tiene que cubrir al responsable para ese paciente, con la ficha
 *       y los recursos que pide el tipo de tarea ({@code Task.code}, propiedades {@code consent-read} y
 *       {@code consent-write} de {@code farm-task-type}). Sin paciente: basta un Consent vigente de acceso al tenant.</li>
 * </ul>
 *
 * <p>Solo se comprueba al crear la tarea y al cambiar su responsable, su paciente o su tipo. Las llamadas internas
 * del servidor quedan fuera. Registrar en {@code hapi.fhir.custom-interceptor-classes}.
 */
@Slf4j
@Interceptor
@Component
@RequiredArgsConstructor
public class ExternalTaskAssignmentInterceptor {

	private final DaoRegistry daoRegistry;
	private final TenantRequests tenantRequests;
	private final ConsentFinder consentFinder;
	private final SystemCatalogs catalogs;

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void onCreated(IBaseResource resource, RequestDetails requestDetails) {
		if (resource instanceof Task task) check(null, task, requestDetails);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void onUpdated(IBaseResource oldResource, IBaseResource newResource, RequestDetails requestDetails) {
		if (newResource instanceof Task task) check(oldResource instanceof Task old ? old : null, task, requestDetails);
	}

	private void check(Task old, Task task, RequestDetails requestDetails) {
		if (requestDetails == null || requestDetails instanceof SystemRequestDetails) return;
		String tenantId = requestDetails.getTenantId();
		if (tenantId == null || tenantId.isBlank() || "DEFAULT".equalsIgnoreCase(tenantId)) return;
		if (!task.hasOwner() || !task.getOwner().hasReference()) return;
		if (old != null && !relevantChange(old, task)) return;

		Optional<Actor> external = externalActor(task.getOwner(), tenantId);
		if (external.isEmpty()) return;
		Actor actor = external.get();

		if (actor.organizationId() != null) requireExternalServiceOrganization(actor.organizationId());

		Optional<String> patientId = patientOf(task);
		Instant now = Instant.now();
		if (patientId.isPresent()) {
			List<Requirement> missing = ExternalAssignmentPolicy.missing(
				ExternalAssignmentPolicy.requirements(catalogs.taskCapabilities(taskTypeOf(task))),
				requirement -> consentFinder.permits(tenantId, patientId.get(), actor, requirement.resourceType(), requirement.access(), now));
			if (!missing.isEmpty()) {
				throw new UnprocessableEntityException(
					"No se puede asignar la tarea a " + task.getOwner().getReference() + ": falta un consentimiento sobre Patient/"
						+ patientId.get() + " que cubra " + String.join(", ", missing.stream().map(Requirement::describe).toList()));
			}
		} else if (!consentFinder.hasActiveGrant(tenantId, actor, now)) {
			throw new UnprocessableEntityException(
				"No se puede asignar la tarea a " + task.getOwner().getReference() + ": no tiene un consentimiento vigente de acceso a este tenant");
		}
	}

	private static boolean relevantChange(Task old, Task task) {
		return !sameReference(old.getOwner(), task.getOwner())
			|| !sameReference(old.getFor(), task.getFor())
			|| !java.util.Objects.equals(taskTypeOf(old), taskTypeOf(task));
	}

	private static boolean sameReference(Reference a, Reference b) {
		return java.util.Objects.equals(a == null ? null : a.getReference(), b == null ? null : b.getReference());
	}

	/** Quién actúa si el responsable es externo al tenant; vacío si es del tenant o no es un profesional ni una organización. */
	private Optional<Actor> externalActor(Reference owner, String tenantId) {
		String type = owner.getReferenceElement().getResourceType();
		String id = owner.getReferenceElement().getIdPart();
		if (id == null || id.isBlank()) return Optional.empty();
		return switch (type == null ? "" : type) {
			case "Practitioner" -> hasRoleInTenant(id, tenantId) ? Optional.empty() : Optional.of(new Actor(id, null));
			case "Organization" -> existsInTenant(id, tenantId) ? Optional.empty() : Optional.of(new Actor(null, id));
			default -> Optional.empty();
		};
	}

	/** Un profesional es del tenant si tiene algún PractitionerRole en él; ante un fallo se trata como externo (más restrictivo). */
	private boolean hasRoleInTenant(String practitionerId, String tenantId) {
		try {
			SearchParameterMap map = new SearchParameterMap();
			// Parámetro propio que compara el texto: el estándar `practitioner` no encuentra a un Practitioner (DEFAULT)
			// buscando desde la partición del tenant (practitioner-role-practitioner-ref.json).
			map.add("practitioner-ref", new TokenParam(null, "Practitioner/" + practitionerId));
			IBundleProvider roles = daoRegistry.getResourceDao(PractitionerRole.class).search(map, tenantRequests.systemIn(tenantId));
			return !roles.getResources(0, 1).isEmpty();
		} catch (RuntimeException e) {
			log.warn("No se pudo comprobar si Practitioner/{} tiene rol en el tenant {}: se trata como externo", practitionerId, tenantId, e);
			return false;
		}
	}

	private boolean existsInTenant(String organizationId, String tenantId) {
		try {
			daoRegistry.getResourceDao(Organization.class).read(new IdType("Organization", organizationId), tenantRequests.systemIn(tenantId));
			return true;
		} catch (ResourceNotFoundException e) {
			return false;
		} catch (RuntimeException e) {
			log.warn("No se pudo comprobar si Organization/{} está en el tenant {}: se trata como externa", organizationId, tenantId, e);
			return false;
		}
	}

	private void requireExternalServiceOrganization(String organizationId) {
		Organization organization;
		try {
			organization = daoRegistry.getResourceDao(Organization.class)
				.read(new IdType("Organization", organizationId), SystemRequestDetails.forAllPartitions());
		} catch (RuntimeException e) {
			throw new UnprocessableEntityException("No se puede asignar la tarea: la organización Organization/" + organizationId + " no existe");
		}
		boolean allowed = organization.getType().stream().map(CodeableConcept::getCoding).flatMap(List::stream)
			.map(Coding::getCode).anyMatch(catalogs::isExternalServiceOrganizationType);
		if (!allowed) {
			throw new UnprocessableEntityException(
				"No se puede asignar la tarea a Organization/" + organizationId + ": no es de un tipo que preste servicios externos");
		}
	}

	private static Optional<String> patientOf(Task task) {
		if (!task.hasFor() || !task.getFor().hasReference()) return Optional.empty();
		IdType reference = new IdType(task.getFor().getReference());
		return "Patient".equals(reference.getResourceType()) && reference.hasIdPart() ? Optional.of(reference.getIdPart()) : Optional.empty();
	}

	/** El código del tipo de tarea: el del catálogo de sistema si lo trae, o el primero que haya. */
	private static String taskTypeOf(Task task) {
		if (!task.hasCode()) return null;
		return task.getCode().getCoding().stream()
			.filter(c -> CatalogProperties.TASK_TYPE_SYSTEM.equals(c.getSystem())).map(Coding::getCode).findFirst()
			.or(() -> task.getCode().getCoding().stream().map(Coding::getCode).findFirst())
			.orElse(null);
	}
}
