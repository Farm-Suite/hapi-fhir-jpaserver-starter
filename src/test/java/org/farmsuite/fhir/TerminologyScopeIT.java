package org.farmsuite.fhir;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Alcance de la terminología (CodeSystem y ValueSet) contra un servidor real: son particionables por
 * tenant, los de {@code DEFAULT} (catálogo de Sistema) los lee cualquiera y solo los escribe la cuenta de
 * servicio, y la terminología de un tenant la lee cualquier miembro y la escribe quien tenga
 * {@code CodeSystem.rscud} / {@code ValueSet.rscud}. Usa sus propias particiones ({@code term-x},
 * {@code term-y}).
 */
class TerminologyScopeIT extends AbstractFarmSuiteServerIT {

	private static final String TENANT_X = "term-x";
	private static final String TENANT_Y = "term-y";
	private static boolean setUp;

	@BeforeEach
	void createTenantsAndTerminology() {
		if (setUp) return;
		asServiceAccount("pr-admin");
		createPartition(11, TENANT_X);
		createPartition(12, TENANT_Y);
		clientFor(TENANT_X).update().resource(codeSystem("cs-x", "http://farmsuite.test/term/cs-x")).execute();
		clientFor(TENANT_X).update().resource(valueSet("vs-x", "http://farmsuite.test/term/vs-x")).execute();
		clientFor("DEFAULT").update().resource(codeSystem("cs-term-system", "http://farmsuite.test/term/cs-system")).execute();
		clientFor("DEFAULT").update().resource(valueSet("vs-term-system", "http://farmsuite.test/term/vs-system")).execute();
		setUp = true;
	}

	private static CodeSystem codeSystem(String id, String url) {
		CodeSystem codeSystem = new CodeSystem();
		codeSystem.setId(id);
		codeSystem.setUrl(url);
		codeSystem.setStatus(Enumerations.PublicationStatus.ACTIVE);
		codeSystem.setContent(Enumerations.CodeSystemContentMode.COMPLETE);
		codeSystem.addConcept().setCode("c").setDisplay("C");
		return codeSystem;
	}

	private static ValueSet valueSet(String id, String url) {
		ValueSet valueSet = new ValueSet();
		valueSet.setId(id);
		valueSet.setUrl(url);
		valueSet.setStatus(Enumerations.PublicationStatus.ACTIVE);
		return valueSet;
	}

	/** El id lo asigna el servidor: quien no es la cuenta de servicio crea sin id. */
	private static <T extends Resource> T withoutId(T resource) {
		resource.setId((String) null);
		return resource;
	}

	private int countByUrl(String tenant, Class<? extends Resource> type, String url) {
		Bundle found = clientFor(tenant).search().forResource(type).where(CodeSystem.URL.matches().value(url))
			.returnBundle(Bundle.class).execute();
		return found.getEntry().size();
	}

	// --- Particionables ---

	@Test
	void aCodeSystemCreatedInATenant_isNotVisibleInAnother() {
		asServiceAccount("pr-admin");

		assertThat(clientFor(TENANT_X).read().resource(CodeSystem.class).withId("cs-x").execute()).isNotNull();
		assertThatThrownBy(() -> clientFor(TENANT_Y).read().resource(CodeSystem.class).withId("cs-x").execute())
			.isInstanceOf(ResourceNotFoundException.class);
		// La búsqueda tarda unos segundos en indexar: primero se espera a verlo en su tenant.
		await().untilAsserted(() -> assertThat(countByUrl(TENANT_X, CodeSystem.class, "http://farmsuite.test/term/cs-x")).isEqualTo(1));
		assertThat(countByUrl(TENANT_Y, CodeSystem.class, "http://farmsuite.test/term/cs-x")).isZero();
	}

	@Test
	void aValueSetCreatedInATenant_isNotVisibleInAnother() {
		asServiceAccount("pr-admin");

		assertThat(clientFor(TENANT_X).read().resource(ValueSet.class).withId("vs-x").execute()).isNotNull();
		assertThatThrownBy(() -> clientFor(TENANT_Y).read().resource(ValueSet.class).withId("vs-x").execute())
			.isInstanceOf(ResourceNotFoundException.class);
		await().untilAsserted(() -> assertThat(countByUrl(TENANT_X, ValueSet.class, "http://farmsuite.test/term/vs-x")).isEqualTo(1));
		assertThat(countByUrl(TENANT_Y, ValueSet.class, "http://farmsuite.test/term/vs-x")).isZero();
	}

	// --- DEFAULT: todos leen, solo la cuenta de servicio escribe ---

	@Test
	void anyAuthenticatedUser_readsTheSystemTerminology() {
		// Sin permisos útiles y de un tenant que no es el de la terminología.
		asHumanOf(TENANT_Y, "pr-7", null, List.of("Patient.rs"));

		assertThat(clientFor("DEFAULT").read().resource(CodeSystem.class).withId("cs-term-system").execute()).isNotNull();
		assertThat(clientFor("DEFAULT").read().resource(ValueSet.class).withId("vs-term-system").execute()).isNotNull();
	}

	@Test
	void aUserWithEveryPermission_cannotWriteTheSystemTerminology() {
		asHumanOf(TENANT_X, "pr-7", null, List.of("*.rscud"));

		assertThatThrownBy(() -> clientFor("DEFAULT").update().resource(codeSystem("cs-term-intruder", "http://farmsuite.test/term/intruder")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> clientFor("DEFAULT").update().resource(valueSet("vs-term-intruder", "http://farmsuite.test/term/intruder-vs")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> clientFor("DEFAULT").update().resource(codeSystem("cs-term-system", "http://farmsuite.test/term/cs-system")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void theServiceAccount_writesTheSystemTerminology() {
		asServiceAccount("pr-admin");

		assertThat(clientFor("DEFAULT").update().resource(codeSystem("cs-term-svc", "http://farmsuite.test/term/svc")).execute().getResource()).isNotNull();
		assertThat(clientFor("DEFAULT").update().resource(valueSet("vs-term-svc", "http://farmsuite.test/term/svc-vs")).execute().getResource()).isNotNull();
	}

	// --- Un tenant: lee cualquier miembro, escribir exige permiso ---

	@Test
	void aMember_readsTheTenantTerminology_whateverTheirRole() {
		asHumanOf(TENANT_X, "pr-7", null, List.of("Patient.rs"));

		assertThat(clientFor(TENANT_X).read().resource(CodeSystem.class).withId("cs-x").execute()).isNotNull();
		assertThat(clientFor(TENANT_X).read().resource(ValueSet.class).withId("vs-x").execute()).isNotNull();
	}

	@Test
	void aMemberWithoutTheWritePermission_cannotWriteTheTenantTerminology() {
		asHumanOf(TENANT_X, "pr-7", null, List.of("Patient.rs"));

		assertThatThrownBy(() -> clientFor(TENANT_X).update().resource(codeSystem("cs-x-no", "http://farmsuite.test/term/cs-x-no")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> clientFor(TENANT_X).update().resource(valueSet("vs-x-no", "http://farmsuite.test/term/vs-x-no")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void codeSystemRscud_writesCodeSystemsButNotValueSets() {
		asHumanOf(TENANT_X, "pr-7", null, List.of("CodeSystem.rscud"));

		assertThat(clientFor(TENANT_X).create().resource(withoutId(codeSystem("cs-x-w", "http://farmsuite.test/term/cs-x-w"))).execute().getResource()).isNotNull();
		assertThatThrownBy(() -> clientFor(TENANT_X).update().resource(valueSet("vs-x-w-no", "http://farmsuite.test/term/vs-x-w-no")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void valueSetRscud_writesValueSetsButNotCodeSystems() {
		asHumanOf(TENANT_X, "pr-7", null, List.of("ValueSet.rscud"));

		assertThat(clientFor(TENANT_X).create().resource(withoutId(valueSet("vs-x-w", "http://farmsuite.test/term/vs-x-w"))).execute().getResource()).isNotNull();
		assertThatThrownBy(() -> clientFor(TENANT_X).update().resource(codeSystem("cs-x-w-no", "http://farmsuite.test/term/cs-x-w-no")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}
}
