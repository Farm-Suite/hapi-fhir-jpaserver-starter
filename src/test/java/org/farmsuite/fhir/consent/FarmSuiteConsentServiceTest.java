package org.farmsuite.fhir.consent;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentOutcome;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.farmsuite.fhir.consent.ConsentMatcher.Access;
import org.farmsuite.fhir.consent.ConsentMatcher.Actor;
import org.farmsuite.fhir.consent.TenantAccessService.Kind;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Location;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * El control de acceso a datos de personas: lo que un profesional que NO es del tenant (entra por un
 * Consent) puede ver y escribir, que lo denegado se audita una sola vez por petición, y que la cuenta de
 * servicio y las lecturas internas no se ven afectadas.
 */
class FarmSuiteConsentServiceTest {

	private static final String TENANT = "farm-a";
	private static final Actor DOCTOR = new Actor("dr-1", "org-ext");

	private UserSessionRepository sessions;
	private PatientSubjectResolver resolver;
	private ConsentFinder consents;
	private DaoRegistry daoRegistry;
	private IFhirResourceDao<AuditEvent> auditDao;
	private IFhirResourceDao<Task> taskDao;
	private IFhirResourceDao<PractitionerRole> roleDao;
	private TenantRequests tenantRequests;
	private TenantAccessService tenantAccess;
	private FarmSuiteConsentService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		sessions = mock(UserSessionRepository.class);
		resolver = mock(PatientSubjectResolver.class);
		consents = mock(ConsentFinder.class);
		daoRegistry = mock(DaoRegistry.class);
		auditDao = mock(IFhirResourceDao.class);
		taskDao = mock(IFhirResourceDao.class);
		roleDao = mock(IFhirResourceDao.class);
		tenantRequests = mock(TenantRequests.class);
		tenantAccess = mock(TenantAccessService.class);
		when(daoRegistry.getResourceDao(AuditEvent.class)).thenReturn(auditDao);
		when(daoRegistry.getResourceDao(Task.class)).thenReturn(taskDao);
		when(daoRegistry.getResourceDao(PractitionerRole.class)).thenReturn(roleDao);
		when(tenantRequests.systemIn(anyString())).thenReturn(new SystemRequestDetails());
		when(sessions.isAdmin()).thenReturn(false);
		service = new FarmSuiteConsentService(sessions, resolver, consents, daoRegistry, tenantRequests, tenantAccess, false);
		asExternalDoctor();
	}

	// --- sesión y petición ---

	private void asExternalDoctor() {
		asSession("dr-1", "org-ext", Kind.BY_CONSENT);
	}

	private void asMemberOf(String organizationId) {
		asSession("pr-9", organizationId, Kind.OWN_TENANT);
	}

	private void asSession(String practitionerId, String organizationId, Kind kind) {
		UserSessionModel session = mock(UserSessionModel.class);
		when(session.getPractitionerId()).thenReturn(practitionerId);
		when(session.getOrganizationId()).thenReturn(organizationId);
		when(sessions.getUserSession()).thenReturn(Optional.of(session));
		when(tenantAccess.resolve(any())).thenReturn(kind);
	}

	private static ServletRequestDetails request(String tenant) {
		ServletRequestDetails details = new ServletRequestDetails();
		details.setTenantId(tenant);
		return details;
	}

	private void patientOf(PatientFacts facts) {
		when(resolver.factsOf(any(), anyString(), any())).thenReturn(Optional.of(facts));
	}

	private void consentGranted(boolean granted) {
		when(consents.permits(anyString(), anyString(), any(Actor.class), anyString(), any(Access.class), any())).thenReturn(granted);
	}

	private static Observation observation(String id) {
		Observation o = new Observation();
		o.setId("Observation/" + id);
		return o;
	}

	private static Task task(String id, String owner) {
		Task t = new Task();
		t.setId("Task/" + id);
		if (owner != null) t.getOwner().setReference(owner);
		return t;
	}

	// --- la cuenta de servicio y las operaciones internas ---

	@Test
	void theServiceAccountAndInternalCallsAreAuthorizedWithoutAnyCheck() {
		when(sessions.isAdmin()).thenReturn(true);
		assertThat(service.startOperation(request(TENANT), null)).isEqualTo(ConsentOutcome.AUTHORIZED);

		when(sessions.isAdmin()).thenReturn(false);
		assertThat(service.startOperation(new SystemRequestDetails(), null)).isEqualTo(ConsentOutcome.AUTHORIZED);
	}

	@Test
	void anyoneElseProceedsToTheResourceLevelChecks() {
		assertThat(service.startOperation(request(TENANT), null)).isEqualTo(ConsentOutcome.PROCEED);
	}

	@Test
	void writesByTheServiceAccountOrInternalCallsAreNeverChecked() {
		Observation o = observation("o1");

		assertThat(service.mayWrite(null, o, Access.WRITE)).isTrue();
		assertThat(service.mayWrite(new SystemRequestDetails(), o, Access.WRITE)).isTrue();
		when(sessions.isAdmin()).thenReturn(true);
		assertThat(service.mayWrite(request(TENANT), o, Access.WRITE)).isTrue();
		verifyNoInteractions(resolver, consents);
	}

	// --- un externo (entra por Consent) ---

	@Test
	void anExternalSeesAPatientsDataOnlyWithAConsentCoveringThem() {
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		consentGranted(true);
		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.PROCEED);

		consentGranted(false);
		assertThat(service.canSeeResource(request(TENANT), observation("o2"), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void theConsentIsLookedUpForThePatientTheActorTheTypeAndTheMode() {
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		consentGranted(true);

		service.canSeeResource(request(TENANT), observation("o1"), null);

		verify(consents).permits(eq(TENANT), eq("p1"), eq(DOCTOR), eq("Observation"), eq(Access.READ), any());
	}

	@Test
	void anAnimalOrATreeIsNotOpenedToAnExternalJustForNotBeingAPerson() {
		patientOf(new PatientFacts("p1", SubjectKind.ANIMAL, null));
		consentGranted(false);

		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void aPatientThatCouldNotBeReadIsDeniedFailClosed() {
		patientOf(PatientFacts.unresolved("p1"));
		consentGranted(true);

		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void aClinicalResourceWithoutAnIdentifiablePatientIsDeniedToAnExternal() {
		when(resolver.factsOf(any(), anyString(), any())).thenReturn(Optional.empty());

		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void resourcesThatAreNotAboutAPatientAreNotDecidedHere() {
		assertThat(service.canSeeResource(request(TENANT), new Organization(), null)).isEqualTo(ConsentOutcome.PROCEED);
		verifyNoInteractions(resolver, consents);
	}

	@Test
	void anExternalSeesTheTasksAssignedToThemAndNoOthers() {
		assertThat(service.canSeeResource(request(TENANT), task("t1", "Practitioner/dr-1"), null)).isEqualTo(ConsentOutcome.PROCEED);
		assertThat(service.canSeeResource(request(TENANT), task("t2", "Practitioner/otro"), null)).isEqualTo(ConsentOutcome.REJECT);
		assertThat(service.canSeeResource(request(TENANT), task("t3", null), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void aTaskOfTheExternalsOrganizationIsTheirsToo() {
		assertThat(service.canSeeResource(request(TENANT), task("t1", "Organization/org-ext"), null)).isEqualTo(ConsentOutcome.PROCEED);
		assertThat(service.canSeeResource(request(TENANT), task("t2", "Organization/org-farm"), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void aTaskAssignedToAPractitionerRoleIsTheirsIfTheRoleIsOfThem() {
		PractitionerRole role = new PractitionerRole();
		role.getPractitioner().setReference("Practitioner/dr-1");
		when(roleDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(role);

		assertThat(service.canSeeResource(request(TENANT), task("t1", "PractitionerRole/r1"), null)).isEqualTo(ConsentOutcome.PROCEED);
	}

	@Test
	void ifTheRoleCannotBeReadTheTaskIsNotTheirs() {
		when(roleDao.read(any(IdType.class), any(RequestDetails.class))).thenThrow(new RuntimeException("no existe"));

		assertThat(service.canSeeResource(request(TENANT), task("t1", "PractitionerRole/r1"), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void anExternalNeverDeletesATaskEvenIfItIsTheirs() {
		assertThat(service.mayWrite(request(TENANT), task("t1", "Practitioner/dr-1"), Access.DELETE)).isFalse();
		assertThat(service.mayWrite(request(TENANT), task("t1", "Practitioner/dr-1"), Access.WRITE)).isTrue();
	}

	@Test
	void anExternalCannotCreateTasks_theyBelongToThoseOfTheTenant() {
		assertThat(service.mayCreate(request(TENANT), task("t1", "Practitioner/dr-1"))).isFalse();
	}

	@Test
	void anExternalMayUpdateTheirTaskButNotRedirectItToSomeoneElseOrAnotherPatient() {
		Task before = task("t1", "Practitioner/dr-1");
		before.getFor().setReference("Patient/p1");
		Task sameAssignment = task("t1", "Practitioner/dr-1");
		sameAssignment.getFor().setReference("Patient/p1");
		sameAssignment.setDescription("acepto la tarea");
		Task reassigned = task("t1", "Practitioner/otro");
		reassigned.getFor().setReference("Patient/p1");
		Task otherPatient = task("t1", "Practitioner/dr-1");
		otherPatient.getFor().setReference("Patient/p2");

		assertThat(service.mayUpdate(request(TENANT), before, sameAssignment)).isTrue();
		assertThat(service.mayUpdate(request(TENANT), before, reassigned)).isFalse();
		assertThat(service.mayUpdate(request(TENANT), before, otherPatient)).isFalse();
	}

	@Test
	void anExternalCannotChangeTheTypeOfTheirTask() {
		Task before = task("t1", "Practitioner/dr-1");
		before.getCode().addCoding().setSystem("s").setCode("milking");
		Task changed = task("t1", "Practitioner/dr-1");
		changed.getCode().addCoding().setSystem("s").setCode("slaughter");

		assertThat(service.mayUpdate(request(TENANT), before, changed)).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void anExternalReadsTheLocationOfOneOfTheirOwnTasksButNeverWritesIt() {
		IBundleProvider tasks = mock(IBundleProvider.class);
		when(tasks.getAllResources()).thenReturn(List.of(task("t1", "Practitioner/dr-1")));
		when(taskDao.search(any(), any(RequestDetails.class))).thenReturn(tasks);
		Location nave = new Location();
		nave.setId("Location/l1");

		assertThat(service.canSeeResource(request(TENANT), nave, null)).isEqualTo(ConsentOutcome.PROCEED);
		assertThat(service.mayWrite(request(TENANT), nave, Access.WRITE)).isFalse();
	}

	@Test
	void aLocationThatIsNotTheContextOfAnyOwnTaskIsDenied() {
		IBundleProvider tasks = mock(IBundleProvider.class);
		when(tasks.getAllResources()).thenReturn(List.of(task("t1", "Practitioner/otro")));
		when(taskDao.search(any(), any(RequestDetails.class))).thenReturn(tasks);
		Location nave = new Location();
		nave.setId("Location/l1");

		assertThat(service.canSeeResource(request(TENANT), nave, null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void ifTheTasksCannotBeSearchedTheLocationIsDenied() {
		when(taskDao.search(any(), any(RequestDetails.class))).thenThrow(new RuntimeException("caído"));
		Location nave = new Location();
		nave.setId("Location/l1");

		assertThat(service.canSeeResource(request(TENANT), nave, null)).isEqualTo(ConsentOutcome.REJECT);
	}

	// --- un miembro del tenant ---

	@Test
	void aMemberOfTheOrganizationThatManagesThePatientSeesTheirData() {
		asMemberOf("org-farm");
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		when(resolver.isWithin(anyString(), anyString(), anyString(), any())).thenReturn(true);

		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.PROCEED);
		verify(consents, never()).permits(any(), any(), any(Actor.class), any(), any(Access.class), any());
	}

	@Test
	void aMemberOfAnotherOrganizationNeedsAConsentToo() {
		asMemberOf("org-otra");
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		when(resolver.isWithin(anyString(), anyString(), anyString(), any())).thenReturn(false);
		consentGranted(false);

		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void aRequestWithoutATenantIsNotDecidedHere() {
		asMemberOf("org-farm");

		assertThat(service.canSeeResource(request(null), observation("o1"), null)).isEqualTo(ConsentOutcome.PROCEED);
		verifyNoInteractions(resolver);
	}

	@Test
	void animalsAndTreesAreOpenToMembersOfTheTenant() {
		asMemberOf("org-otra");
		patientOf(new PatientFacts("p1", SubjectKind.ANIMAL, null));

		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.PROCEED);
	}

	// --- consentimientos: caché y auditoría ---

	@Test
	void theConsentLookupIsCachedWithinOneRequest() {
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		consentGranted(true);
		ServletRequestDetails request = request(TENANT);

		service.canSeeResource(request, observation("o1"), null);
		service.canSeeResource(request, observation("o2"), null);
		service.canSeeResource(request, observation("o3"), null);

		verify(consents, times(1)).permits(anyString(), anyString(), any(Actor.class), anyString(), any(Access.class), any());
	}

	@Test
	void everythingDeniedInARequestLeavesASingleAuditEventWhenItFinishes() {
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		consentGranted(false);
		ServletRequestDetails request = request(TENANT);

		service.canSeeResource(request, observation("o1"), null);
		service.canSeeResource(request, observation("o2"), null);
		service.canSeeResource(request, observation("o3"), null);
		verify(auditDao, never()).create(any(AuditEvent.class), any(RequestDetails.class));
		service.completeOperationSuccess(request, null);

		ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
		verify(auditDao, times(1)).create(event.capture(), any(RequestDetails.class));
		assertThat(event.getValue().getEntity()).extracting(e -> e.getWhat().getReference())
			.containsExactly("Observation/o1", "Observation/o2", "Observation/o3");
		// una segunda finalización no vuelve a auditar lo ya registrado
		service.completeOperationFailure(request, null, null);
		verify(auditDao, times(1)).create(any(AuditEvent.class), any(RequestDetails.class));
	}

	@Test
	void aRequestWithNothingDeniedLeavesNoAuditEvent() {
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		consentGranted(true);
		ServletRequestDetails request = request(TENANT);

		service.canSeeResource(request, observation("o1"), null);
		service.completeOperationSuccess(request, null);

		verify(auditDao, never()).create(any(AuditEvent.class), any(RequestDetails.class));
	}

	@Test
	void aDeniedWriteIsAuditedImmediately() {
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		consentGranted(false);

		assertThat(service.mayWrite(request(TENANT), observation("o1"), Access.WRITE)).isFalse();

		verify(auditDao).create(any(AuditEvent.class), any(RequestDetails.class));
	}

	@Test
	void ifTheAuditFailsTheDenialIsStillReturnedAndNothingIsThrown() {
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		consentGranted(false);
		when(auditDao.create(any(AuditEvent.class), any(RequestDetails.class))).thenThrow(new RuntimeException("base caída"));

		assertThatCode(() -> assertThat(service.mayWrite(request(TENANT), observation("o1"), Access.WRITE)).isFalse()).doesNotThrowAnyException();
	}

	@Test
	void theOrganizationIsRequiredToTouchPeopleDataWhenConfiguredSo() {
		service = new FarmSuiteConsentService(sessions, resolver, consents, daoRegistry, tenantRequests, tenantAccess, true);
		asSession("pr-9", null, Kind.OWN_TENANT);
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));
		consentGranted(false);

		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.REJECT);
	}

	@Test
	void withoutTheStrictSettingASessionWithoutOrganizationPasses_theOwnerMayHaveNone() {
		asSession("pr-9", null, Kind.OWN_TENANT);
		patientOf(new PatientFacts("p1", SubjectKind.PERSON, "org-farm"));

		assertThat(service.canSeeResource(request(TENANT), observation("o1"), null)).isEqualTo(ConsentOutcome.PROCEED);
	}
}
