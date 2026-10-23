package org.farmsuite.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.common.TestContainerHelper;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.IntegerType;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.Practitioner;
import org.hl7.fhir.r5.model.PractitionerRole;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Montaje común de los tests de integración contra un servidor HAPI real: la configuración completa
 * de FarmSuite (particiones por tenant, FarmSuiteRules, interceptores registrados en application.yaml)
 * y Postgres en Testcontainers. Solo se simula la sesión (JWT) y Administration-MIC/Keycloak son un
 * servidor HTTP local que registra las llamadas del espejo: nada toca ningún servicio real. (Un puerto
 * muerto no sirve: {@code TaskOwnerSyncNotify} pide el token OAuth sin timeout propio dentro de la
 * transacción de la Task.)
 *
 * <p>Todas las subclases comparten la misma configuración de Spring, así que el servidor arranca una
 * sola vez (≈35 s) para todas.
 *
 * <p>Datos base (tenant {@code farm-a}): practitioners {@code pr-admin}, {@code pr-7}, {@code pr-9}
 * (partición DEFAULT); organizaciones {@code org-1} (granja), {@code org-1-sub} (cuelga de org-1) y
 * {@code pharmacy-ext} (externa); y {@code role-9}, que enlaza a pr-9 con org-1.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties = {
	"hapi.fhir.fhir_version=r5",
	"hapi.fhir.cr_enabled=false",
	"hapi.fhir.mdm_enabled=false",
	"hapi.fhir.advanced_lucene_indexing=false",
	"spring.jpa.properties.hibernate.search.enabled=false",
	"spring.jpa.properties.hibernate.default_schema=public",
	// Modo in-memory del SSO: autentica el Bearer sin Keycloak. La sesión que leen FarmSuiteRules y
	// los interceptores la aporta el UserSessionRepository simulado (el modo in-memory no permite
	// fijar practitionerId ni organización).
	"sso.oidc.enabled=false",
	"sso.inmemory.enabled=true",
	"sso.inmemory.users[0].token=test-token",
	"sso.inmemory.users[0].username=tester",
	"sso.inmemory.users[0].type-client=ADMIN",
	"farmsuite.administration.client-secret=test-secret"
})
abstract class AbstractFarmSuiteServerIT {

	protected static final String TENANT = "farm-a";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = TestContainerHelper.newPostgresContainer();

	/** Llamadas recibidas por el Administration-MIC de mentira: "PUT /practitioner-tasks/{id} {json}". */
	protected static final Queue<String> MIRROR_CALLS = new ConcurrentLinkedQueue<>();
	private static final HttpServer STUB = startStub();

	private static HttpServer startStub() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/token", exchange -> reply(exchange, "{\"access_token\":\"stub\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
			server.createContext("/administration/api/practitioner-tasks", exchange -> {
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				MIRROR_CALLS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
				reply(exchange, "{}");
			});
			server.start();
			return server;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void reply(HttpExchange exchange, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		TestContainerHelper.registerPostgresProperties(registry, POSTGRES);
		String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
		registry.add("farmsuite.administration.base-url", () -> base + "/administration/api");
		registry.add("farmsuite.administration.token-endpoint", () -> base + "/token");
	}

	@LocalServerPort
	private int port;

	@MockitoBean
	private UserSessionRepository userSessionRepository;

	private FhirContext ctx;
	/** Cliente sobre la partición del tenant {@value #TENANT}; autenticado con el Bearer de pruebas. */
	protected IGenericClient tenantClient;

	private static boolean baseDataCreated;

	@BeforeEach
	void setUpServer() {
		ctx = FhirContext.forR5Cached();
		ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		asServiceAccount("pr-admin");
		tenantClient = client("http://localhost:" + port + "/fhir/" + TENANT);
		createBaseData();
	}

	private IGenericClient client(String baseUrl) {
		IGenericClient client = ctx.newRestfulGenericClient(baseUrl);
		client.registerInterceptor(new BearerTokenAuthInterceptor("test-token"));
		return client;
	}

	/** Cliente sobre la partición de otro tenant (por URL), autenticado con el Bearer de pruebas. */
	protected IGenericClient clientFor(String tenant) {
		return client("http://localhost:" + port + "/fhir/" + tenant);
	}

	/** Crea una partición de tenant con el id dado (operación de servidor; requiere la cuenta de servicio). */
	protected void createPartition(int id, String name) {
		Parameters in = new Parameters();
		in.addParameter().setName("id").setValue(new IntegerType(id));
		in.addParameter().setName("name").setValue(new CodeType(name));
		client("http://localhost:" + port + "/fhir").operation().onServer()
			.named("$partition-management-create-partition").withParameters(in).execute();
	}

	private void createBaseData() {
		if (baseDataCreated) return;
		Parameters in = new Parameters();
		in.addParameter().setName("id").setValue(new IntegerType(7));
		in.addParameter().setName("name").setValue(new CodeType(TENANT));
		client("http://localhost:" + port + "/fhir").operation().onServer()
			.named("$partition-management-create-partition").withParameters(in).execute();

		// Lo que el escenario real ya tiene: los practitioners viven en DEFAULT (el servidor fuerza esa
		// partición), y HAPI exige que las referencias (agent.who, Task.owner, managingOrganization) existan.
		for (String id : List.of("pr-admin", "pr-7", "pr-9")) {
			Practitioner practitioner = new Practitioner();
			practitioner.setId(id);
			practitioner.addName().setFamily(id);
			tenantClient.update().resource(practitioner).execute();
		}
		organization("org-1", "Granja A", null);
		organization("org-1-sub", "Establo de la Granja A", "org-1");
		organization("pharmacy-ext", "Farmacia externa", null);

		// pr-9 trabaja en org-1: un único PractitionerRole los enlaza, y es lo que se usa como owner
		// cuando una tarea está asignada a la vez a una organización y a un profesional.
		PractitionerRole role = new PractitionerRole();
		role.setId("role-9");
		role.setPractitioner(new Reference("Practitioner/pr-9"));
		role.setOrganization(new Reference("Organization/org-1"));
		tenantClient.update().resource(role).execute();
		baseDataCreated = true;
	}

	private void organization(String id, String name, String partOf) {
		Organization organization = new Organization();
		organization.setId(id);
		organization.setName(name);
		if (partOf != null) organization.setPartOf(new Reference("Organization/" + partOf));
		tenantClient.update().resource(organization).execute();
	}

	/** La cuenta de servicio: sin restricciones de permisos y fuera del control por Consent. */
	protected void asServiceAccount(String practitionerId) {
		UserSessionModel session = mock(UserSessionModel.class);
		when(session.getPractitionerId()).thenReturn(practitionerId);
		when(userSessionRepository.isAdmin()).thenReturn(true);
		when(userSessionRepository.getUserSession()).thenReturn(Optional.of(session));
	}

	/** Un usuario humano sin organización en la sesión (p. ej. el dueño del tenant). */
	protected void asHuman(String practitionerId, List<String> permissions) {
		asHuman(practitionerId, null, permissions);
	}

	/** Un usuario humano que actúa en nombre de {@code organizationId} (la de su sesión o la de su token exchange). */
	protected void asHuman(String practitionerId, String organizationId, List<String> permissions) {
		UserSessionModel session = mock(UserSessionModel.class);
		when(session.getPractitionerId()).thenReturn(practitionerId);
		when(session.getTenantId()).thenReturn(TENANT);
		when(session.getOrganizationId()).thenReturn(organizationId);
		when(session.getTypeClient()).thenReturn(UserSessionModel.TypeClient.USER);
		when(session.getPermission()).thenReturn(permissions);
		when(session.canAccessTenant(TENANT)).thenReturn(true);
		when(userSessionRepository.isAdmin()).thenReturn(false);
		when(userSessionRepository.getUserSession()).thenReturn(Optional.of(session));
	}
}
