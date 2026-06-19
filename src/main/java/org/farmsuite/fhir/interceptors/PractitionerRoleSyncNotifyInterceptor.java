package org.farmsuite.fhir.interceptors;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.usecase.PractitionerRoleNotify;
import org.hl7.fhir.r4.model.PractitionerRole;

@Slf4j
@Interceptor
@RequiredArgsConstructor
public class PractitionerRoleSyncNotifyInterceptor {

	private final PractitionerRoleNotify practitionerRoleNotify;


	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void notifyCreated(
		org.hl7.fhir.instance.model.api.IBaseResource theResource,
		RequestDetails theRequestDetails,
		RequestPartitionId theRequestPartitionId
	) {

		if (!(theResource instanceof PractitionerRole role)) return;

		String practitionerId = role.getPractitioner().getReference();
		String tenantId = theRequestPartitionId.getFirstPartitionNameOrNull();
		String roleId = role.getIdElement().getIdPart();

		log.debug("PractitionerRole CREATED: roleId={}, practitionerId={}, tenant={}", roleId, practitionerId, tenantId);

		practitionerRoleNotify.create(role, tenantId);

	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
	public void notifyUpdated(
		org.hl7.fhir.instance.model.api.IBaseResource theOldResource,
		org.hl7.fhir.instance.model.api.IBaseResource theNewResource,
		RequestDetails theRequestDetails,
		RequestPartitionId theRequestPartitionId) {

		if (!(theNewResource instanceof PractitionerRole role)) return;

		String practitionerId = role.getPractitioner().getReference();
		String tenantId = theRequestPartitionId.getFirstPartitionNameOrNull();
		String roleId = role.getIdElement().getIdPart();

		log.debug("PractitionerRole UPDATED: roleId={}, practitionerId={}, tenant={}", roleId, practitionerId, tenantId);

		practitionerRoleNotify.update(role, tenantId);
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
	public void notifyDeleted(
		org.hl7.fhir.instance.model.api.IBaseResource theResource,
		RequestDetails theRequestDetails,
		RequestPartitionId theRequestPartitionId) {

		if (!(theResource instanceof PractitionerRole role)) return;

		String practitionerId = role.getPractitioner().getReference();
		String tenantId = theRequestPartitionId.getFirstPartitionNameOrNull();
		String roleId = role.getIdElement().getIdPart();

		log.debug("PractitionerRole DELETED: roleId={}, practitionerId={}, tenant={}", roleId, practitionerId, tenantId);

		practitionerRoleNotify.delete(role, tenantId);
	}

}
