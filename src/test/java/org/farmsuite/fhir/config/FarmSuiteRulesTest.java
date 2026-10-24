package org.farmsuite.fhir.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationFlagsEnum;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.IRuleApplier;
import ca.uhn.fhir.rest.server.interceptor.auth.PolicyEnum;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.farmsuite.fhir.consent.TenantAccessService;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La barrera de autorización del servidor FHIR: qué puede hacer cada sesión según el claim
 * {@code permissions}. Se evalúan las reglas reales de HAPI en orden, como hace el interceptor
 * (la primera que decide gana; si ninguna decide, se deniega).
 */
class FarmSuiteRulesTest {

	private static final String TENANT = "farm-a";
	private static final String OTHER_TENANT = "farm-b";

	private final FhirContext fhir = FhirContext.forR5Cached();
	private UserSessionRepository sessions;
	private TenantAccessService tenantAccess;
	private FarmSuiteRules rules;

	@BeforeEach
	void setUp() {
		sessions = mock(UserSessionRepository.class);
		tenantAccess = mock(TenantAccessService.class);
		when(tenantAccess.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(TenantAccessService.Kind.OWN_TENANT);
		rules = new FarmSuiteRules(sessions, fhir, tenantAccess);
	}

	// --- sesiones ---

	private void asServiceAccount() {
		when(sessions.isAdmin()).thenReturn(true);
	}

	private void asNobody() {
		when(sessions.isAdmin()).thenReturn(false);
		when(sessions.getUserSession()).thenReturn(Optional.empty());
	}

	private void asUser(String tenant, String practitionerId, String... permissions) {
		UserSessionModel session = mock(UserSessionModel.class);
		when(session.getUsername()).thenReturn("ana");
		when(session.getTenantId()).thenReturn(tenant);
		when(session.getPractitionerId()).thenReturn(practitionerId);
		when(session.getPermission()).thenReturn(permissions == null ? null : List.of(permissions));
		when(sessions.isAdmin()).thenReturn(false);
		when(sessions.getUserSession()).thenReturn(Optional.of(session));
	}

	// --- evaluación ---

	private enum Verdict { ALLOW, DENY }

	private Verdict decide(RestOperationTypeEnum operation, String type, String tenant, IBaseResource input, IdType id) {
		ServletRequestDetails request = new ServletRequestDetails();
		RestfulServer server = mock(RestfulServer.class);
		when(server.getFhirContext()).thenReturn(fhir);
		request.setServer(server);
		request.setTenantId(tenant);
		request.setResourceName(type);
		request.setRestOperationType(operation);

		for (IAuthRule rule : rules.buildRuleList(request)) {
			var verdict = rule.applyRule(operation, request, input, id, null, mock(IRuleApplier.class),
				EnumSet.noneOf(AuthorizationFlagsEnum.class), Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED);
			if (verdict != null) {
				return verdict.getDecision() == PolicyEnum.ALLOW ? Verdict.ALLOW : Verdict.DENY;
			}
		}
		return Verdict.DENY;
	}

	private Verdict read(String type, String tenant) {
		return decide(RestOperationTypeEnum.READ, type, tenant, null, new IdType(type, "1"));
	}

	private Verdict search(String type, String tenant) {
		return decide(RestOperationTypeEnum.SEARCH_TYPE, type, tenant, null, null);
	}

	private Verdict create(String type, String tenant) {
		IBaseResource resource = fhir.getResourceDefinition(type).newInstance();
		return decide(RestOperationTypeEnum.CREATE, type, tenant, resource, null);
	}

	private Verdict update(String type, String tenant, String id) {
		IBaseResource resource = fhir.getResourceDefinition(type).newInstance();
		resource.setId(new IdType(type, id));
		return decide(RestOperationTypeEnum.UPDATE, type, tenant, resource, new IdType(type, id));
	}

	private Verdict delete(String type, String tenant) {
		return decide(RestOperationTypeEnum.DELETE, type, tenant, null, new IdType(type, "1"));
	}

	// --- cuenta de servicio y anónimos ---

	@Test
	void theServiceAccountCanDoAnythingInAnyTenant() {
		asServiceAccount();

		assertThat(create("Patient", OTHER_TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(delete("Task", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(create("Practitioner", "DEFAULT")).isEqualTo(Verdict.ALLOW);
	}

	@Test
	void withoutASessionNothingIsAllowed() {
		asNobody();

		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.DENY);
		assertThat(search("Task", TENANT)).isEqualTo(Verdict.DENY);
		assertThat(create("Patient", TENANT)).isEqualTo(Verdict.DENY);
	}

	// --- permisos por tipo y acción ---

	@Test
	void readAndSearchPermissionsAllowReadingOnlyThatTypeInThatTenant() {
		asUser(TENANT, "pr-1", "Patient.rs");

		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(search("Patient", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(read("Observation", TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void aPermissionNeverReachesAnotherTenant() {
		asUser(TENANT, "pr-1", "Patient.rscud");

		assertThat(read("Patient", OTHER_TENANT)).isEqualTo(Verdict.DENY);
		assertThat(create("Patient", OTHER_TENANT)).isEqualTo(Verdict.DENY);
		assertThat(delete("Patient", OTHER_TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void readOnlyDoesNotAllowWriting() {
		asUser(TENANT, "pr-1", "Patient.rs");

		assertThat(create("Patient", TENANT)).isEqualTo(Verdict.DENY);
		assertThat(update("Patient", TENANT, "1")).isEqualTo(Verdict.DENY);
		assertThat(delete("Patient", TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void createAloneGrantsOnlyCreating_notReadingNorDeleting() {
		// Nota: en fase de petición HAPI trata un PUT como un posible alta, así que aquí no se afirma nada sobre
		// "actualizar un recurso existente": eso lo corta HAPI al guardar (hook de pre-almacenamiento) y se
		// comprueba con el servidor completo en los *IT.
		asUser(TENANT, "pr-1", "Task.c");

		assertThat(create("Task", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(read("Task", TENANT)).isEqualTo(Verdict.DENY);
		assertThat(delete("Task", TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void updateGrantsWriting() {
		asUser(TENANT, "pr-1", "Task.u");

		assertThat(update("Task", TENANT, "1")).isEqualTo(Verdict.ALLOW);
		assertThat(create("Task", TENANT)).isEqualTo(Verdict.ALLOW); // write() de HAPI cubre create + update
	}

	@Test
	void deleteIsASeparatePermission() {
		asUser(TENANT, "pr-1", "Task.rscu");
		assertThat(delete("Task", TENANT)).isEqualTo(Verdict.DENY);

		asUser(TENANT, "pr-1", "Task.d");
		assertThat(delete("Task", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(read("Task", TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void theWildcardAppliesToEveryResourceTypeOfTheTenant() {
		asUser(TENANT, "pr-1", "*.rscud");

		assertThat(read("Observation", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(create("Task", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(delete("Location", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(read("Observation", OTHER_TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void severalPermissionsAreCombined() {
		asUser(TENANT, "pr-1", "Patient.rs", "Task.rscud");

		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(create("Patient", TENANT)).isEqualTo(Verdict.DENY);
		assertThat(create("Task", TENANT)).isEqualTo(Verdict.ALLOW);
	}

	@Test
	void aMalformedOrUnknownPermissionIsIgnoredWithoutAffectingTheOthers() {
		asUser(TENANT, "pr-1", "bogus", "CONF_READ", "Patient.rx", "NoExisteElTipo.r", "Task.rs");

		assertThat(read("Task", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void aPermissionToPatchIsIgnoredBecauseHapiCannotScopeIt() {
		asUser(TENANT, "pr-1", "Patient.p");

		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.DENY);
		assertThat(decide(RestOperationTypeEnum.PATCH, "Patient", TENANT, null, new IdType("Patient", "1"))).isEqualTo(Verdict.DENY);
	}

	@Test
	void withoutPermissionsOrTenantOnlyTheStructuralRulesApply() {
		asUser(TENANT, "pr-1");
		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.DENY);

		asUser(null, "pr-1", "Patient.rs");
		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.DENY);

		asUser(" ", "pr-1", "Patient.rs");
		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.DENY);
	}

	// --- Practitioner: reglas estructurales que ninguna plantilla de rol puede aflojar ---

	@Test
	void nobodyButTheServiceAccountCreatesPractitioners_evenWithAWildcard() {
		asUser(TENANT, "pr-1", "*.rscud", "Practitioner.rscud");

		assertThat(create("Practitioner", "DEFAULT")).isEqualTo(Verdict.DENY);
		assertThat(create("Practitioner", TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void anyAuthenticatedUserCanReadAnyPractitioner() {
		asUser(TENANT, "pr-1", "Patient.rs");

		assertThat(read("Practitioner", "DEFAULT")).isEqualTo(Verdict.ALLOW);
		assertThat(read("Practitioner", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(search("Practitioner", TENANT)).isEqualTo(Verdict.ALLOW);
	}

	@Test
	void aUserCanOnlyUpdateTheirOwnPractitioner() {
		asUser(TENANT, "pr-1");

		assertThat(update("Practitioner", "DEFAULT", "pr-1")).isEqualTo(Verdict.ALLOW);
		assertThat(update("Practitioner", "DEFAULT", "pr-2")).isEqualTo(Verdict.DENY);
	}

	@Test
	void withoutAPractitionerIdNobodyCanUpdateAPractitioner() {
		asUser(TENANT, null, "Patient.rs");

		assertThat(update("Practitioner", "DEFAULT", "pr-1")).isEqualTo(Verdict.DENY);
	}

	// --- datos de referencia ---

	@Test
	void referenceDataIsReadableByAnyAuthenticatedUserWithoutPermissions() {
		asUser(TENANT, "pr-1");

		for (String type : new String[] {"StructureDefinition", "ValueSet", "CodeSystem"}) {
			assertThat(read(type, "DEFAULT")).as(type).isEqualTo(Verdict.ALLOW);
		}
	}

	@Test
	void referenceDataCannotBeWrittenWithoutAPermissionForTheTenant() {
		asUser(TENANT, "pr-1");

		assertThat(create("StructureDefinition", "DEFAULT")).isEqualTo(Verdict.DENY);
		assertThat(update("ValueSet", "DEFAULT", "vs")).isEqualTo(Verdict.DENY);
	}

	@Test
	void theTenantsOwnTerminologyIsReadableButNotOtherTenants() {
		asUser(TENANT, "pr-1");

		assertThat(read("CodeSystem", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(read("ValueSet", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void writingTerminologyFollowsThePermissionsClaim() {
		asUser(TENANT, "pr-1", "CodeSystem.rscud");

		assertThat(create("CodeSystem", TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(create("CodeSystem", OTHER_TENANT)).isEqualTo(Verdict.DENY);
		assertThat(create("ValueSet", TENANT)).isEqualTo(Verdict.DENY);
	}

	// --- externos por consentimiento ---

	@Test
	void anExternalByConsentGetsTheCoarseRulesOfTheRequestedTenantAndNeverTheirOwnPermissions() {
		when(tenantAccess.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(TenantAccessService.Kind.BY_CONSENT);
		asUser(TENANT, "dr-1", "*.rscud");

		// lee y escribe lo que cuelga de un paciente, en el tenant de la URL
		assertThat(read("Observation", OTHER_TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(create("Observation", OTHER_TENANT)).isEqualTo(Verdict.ALLOW);
		// lee el paciente pero nunca lo escribe ni lo borra
		assertThat(read("Patient", OTHER_TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(update("Patient", OTHER_TENANT, "1")).isEqualTo(Verdict.DENY);
		assertThat(delete("Patient", OTHER_TENANT)).isEqualTo(Verdict.DENY);
		// y nada que no sea de esos tipos
		assertThat(create("Organization", OTHER_TENANT)).isEqualTo(Verdict.DENY);
		assertThat(read("Organization", OTHER_TENANT)).isEqualTo(Verdict.DENY);
	}

	@Test
	void whoEntersATenantIsDecidedByTenantAccess_notByTheRules() {
		// Las reglas por consentimiento se montan sobre el tenant de la URL; que esa persona pueda entrar
		// ahí lo decide TenantAccessService (un Consent activo), que es lo que se simula aquí.
		when(tenantAccess.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(TenantAccessService.Kind.OWN_TENANT);
		asUser(TENANT, "dr-1", "Patient.rs");

		assertThat(read("Observation", TENANT)).isEqualTo(Verdict.DENY);
		assertThat(read("Patient", TENANT)).isEqualTo(Verdict.ALLOW);
	}

	@Test
	void anExternalByConsentCanReadTheTenantsLocationsAndTerminologyButNotWriteThem() {
		when(tenantAccess.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(TenantAccessService.Kind.BY_CONSENT);
		asUser(TENANT, "dr-1", "*.rscud");

		assertThat(read("Location", OTHER_TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(create("Location", OTHER_TENANT)).isEqualTo(Verdict.DENY);
		assertThat(read("CodeSystem", OTHER_TENANT)).isEqualTo(Verdict.ALLOW);
		assertThat(create("CodeSystem", OTHER_TENANT)).isEqualTo(Verdict.DENY);
	}
}
