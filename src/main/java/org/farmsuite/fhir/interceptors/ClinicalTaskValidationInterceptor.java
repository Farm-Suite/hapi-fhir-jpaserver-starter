package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Task;
import org.springframework.stereotype.Component;

/**
 * Invariante de las tareas clínicas: una {@code Task} que declara un paciente ({@code Task.for})
 * debe apuntar a un {@code Patient} que exista <b>en la misma partición</b> (tenant) que la tarea.
 * Una tarea operativa (sin {@code for}) no se toca.
 *
 * <p>Sin esto la trazabilidad por paciente se rompe en silencio: una referencia a un Patient
 * inexistente, de otro tipo, o de otro tenant, deja la tarea huérfana de su historia clínica —y
 * el servidor tiene {@code allow_references_across_partitions: true}, así que HAPI no lo impide.
 * Lo comprueba el servidor, no el formulario, porque cualquier cliente puede hablar directo con
 * la API.
 */
@Interceptor
@Component
@RequiredArgsConstructor
public class ClinicalTaskValidationInterceptor {

	private final DaoRegistry daoRegistry;

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void onCreated(IBaseResource theResource, RequestDetails theRequestDetails, RequestPartitionId theRequestPartitionId) {
		validate(theResource, theRequestDetails, theRequestPartitionId);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void onUpdated(IBaseResource theOldResource, IBaseResource theNewResource, RequestDetails theRequestDetails, RequestPartitionId theRequestPartitionId) {
		validate(theNewResource, theRequestDetails, theRequestPartitionId);
	}

	private void validate(IBaseResource resource, RequestDetails requestDetails, RequestPartitionId partitionId) {
		if (!(resource instanceof Task task) || !task.hasFor() || !task.getFor().hasReference()) return;

		String reference = task.getFor().getReference();
		IdType patientId = new IdType(reference);
		if (!"Patient".equals(patientId.getResourceType()) || !patientId.hasIdPart()) {
			throw new UnprocessableEntityException(
				"Task.for debe ser una referencia a un Patient (Patient/{id}); se recibió '" + reference + "'");
		}

		String tenantId = TaskOwnerSyncInterceptor.resolveTenantId(requestDetails, partitionId);
		if (tenantId == null) return; // sin tenant resoluble no hay partición contra la que comprobar

		try {
			daoRegistry.getResourceDao(Patient.class).read(
				new IdType("Patient", patientId.getIdPart()),
				new SystemRequestDetails().setRequestPartitionId(RequestPartitionId.fromPartitionName(tenantId)));
		} catch (ResourceNotFoundException | ResourceGoneException e) {
			throw new UnprocessableEntityException(
				"Task.for apunta a Patient/" + patientId.getIdPart() + ", que no existe en el tenant '" + tenantId + "'");
		}
	}
}
