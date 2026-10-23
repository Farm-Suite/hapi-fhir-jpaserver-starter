package org.farmsuite.fhir;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Period;
import org.hl7.fhir.r5.model.Practitioner;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aislamiento entre tenants contra un servidor real: un usuario con sesión del tenant {@code farm-a}
 * (con los permisos más amplios, {@code *.rscud}) no puede leer ni escribir en la partición de
 * {@code farm-b} cambiando el segmento de la URL, ni tocar el Practitioner de otro profesional.
 *
 * <p>Es la red de seguridad previa a dar acceso a externos por Consent
 * ({@code arquitectura-trazabilidad-clinica}): hasta ahora ningún test pedía {@code /fhir/<otro-tenant>/...}
 * con un token de otro tenant.
 */
class CrossTenantIsolationIT extends AbstractFarmSuiteServerIT {

	private static final String OTHER_TENANT = "farm-b";
	private static final List<String> EVERYTHING = List.of("*.rscud");
	private static boolean otherTenantCreated;

	@BeforeEach
	void createOtherTenant() {
		if (otherTenantCreated) return;
		asServiceAccount("pr-admin");
		createPartition(8, OTHER_TENANT);

		Organization organization = new Organization();
		organization.setId("org-b");
		organization.setName("Granja B");
		clientFor(OTHER_TENANT).update().resource(organization).execute();

		CodeSystem codeSystem = new CodeSystem();
		codeSystem.setId("cs-b");
		codeSystem.setUrl("http://farmsuite.test/cs-b");
		codeSystem.setStatus(Enumerations.PublicationStatus.ACTIVE);
		codeSystem.setContent(Enumerations.CodeSystemContentMode.COMPLETE);
		codeSystem.addConcept().setCode("x").setDisplay("X");
		clientFor(OTHER_TENANT).update().resource(codeSystem).execute();

		CodeSystem own = new CodeSystem();
		own.setId("cs-a");
		own.setUrl("http://farmsuite.test/cs-a");
		own.setStatus(Enumerations.PublicationStatus.ACTIVE);
		own.setContent(Enumerations.CodeSystemContentMode.COMPLETE);
		own.addConcept().setCode("y").setDisplay("Y");
		tenantClient.update().resource(own).execute();

		CodeSystem system = new CodeSystem();
		system.setId("cs-system");
		system.setUrl("http://farmsuite.test/cs-system");
		system.setStatus(Enumerations.PublicationStatus.ACTIVE);
		system.setContent(Enumerations.CodeSystemContentMode.COMPLETE);
		system.addConcept().setCode("z").setDisplay("Z");
		clientFor("DEFAULT").update().resource(system).execute();

		// Externos con (o sin) un Consent en farm-b. Practitioner vive en DEFAULT.
		for (String id : List.of("pr-ext-1", "pr-ext-revoked", "pr-ext-expired")) {
			Practitioner practitioner = new Practitioner();
			practitioner.setId(id);
			practitioner.addName().setFamily(id);
			tenantClient.update().resource(practitioner).execute();
		}
		accessConsent("Practitioner/pr-ext-1", Consent.ConsentState.ACTIVE, null);
		accessConsent("Practitioner/pr-ext-revoked", Consent.ConsentState.INACTIVE, null);
		accessConsent("Practitioner/pr-ext-expired", Consent.ConsentState.ACTIVE, new Period().setEnd(Date.from(Instant.parse("2026-01-01T00:00:00Z"))));
		accessConsent("Organization/pharmacy-ext", Consent.ConsentState.ACTIVE, null);
		otherTenantCreated = true;
	}

	/** Un Consent en farm-b que concede acceso a {@code grantee}, sin sujeto: es lo que deja entrar al tenant. */
	private void accessConsent(String grantee, Consent.ConsentState status, Period period) {
		Consent consent = new Consent();
		consent.setStatus(status);
		consent.setDecision(Enumerations.ConsentProvisionType.PERMIT);
		consent.addGrantee(new Reference(grantee));
		if (period != null) consent.setPeriod(period);
		clientFor(OTHER_TENANT).create().resource(consent).execute();
	}

	@Test
	void anExternalWithAConsentInTheTenant_readsItsTerminology() {
		asHuman("pr-ext-1", "org-1", EVERYTHING);

		assertThat(clientFor(OTHER_TENANT).read().resource(CodeSystem.class).withId("cs-b").execute()).isNotNull();
	}

	@Test
	void anExternalWithAConsentInTheTenant_neverWritesItsTerminology() {
		asHuman("pr-ext-1", "org-1", EVERYTHING);
		CodeSystem codeSystem = new CodeSystem();
		codeSystem.setId("cs-by-external");
		codeSystem.setUrl("http://farmsuite.test/cs-by-external");
		codeSystem.setStatus(Enumerations.PublicationStatus.ACTIVE);
		codeSystem.setContent(Enumerations.CodeSystemContentMode.COMPLETE);

		assertThatThrownBy(() -> clientFor(OTHER_TENANT).update().resource(codeSystem).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void theConsentOnlyGivesWhatItsForForNow_notTheRestOfTheTenant() {
		asHuman("pr-ext-1", "org-1", EVERYTHING);

		assertThatThrownBy(() -> clientFor(OTHER_TENANT).read().resource(Organization.class).withId("org-b").execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aRevokedOrExpiredConsent_doesNotLetIn() {
		asHuman("pr-ext-revoked", "org-1", EVERYTHING);
		assertThatThrownBy(() -> clientFor(OTHER_TENANT).read().resource(CodeSystem.class).withId("cs-b").execute())
			.isInstanceOf(ForbiddenOperationException.class);

		asHuman("pr-ext-expired", "org-1", EVERYTHING);
		assertThatThrownBy(() -> clientFor(OTHER_TENANT).read().resource(CodeSystem.class).withId("cs-b").execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void aConsentGrantedToTheOrganization_letsInThoseWhoActForIt() {
		asHuman("pr-9", "pharmacy-ext", EVERYTHING);

		assertThat(clientFor(OTHER_TENANT).read().resource(CodeSystem.class).withId("cs-b").execute()).isNotNull();
	}

	/** Guarda contra el exceso de celo: lo propio y el catálogo de Sistema siguen siendo legibles. */
	@Test
	void readingTheOwnTerminology_andTheSystemCatalog_stillWorks() {
		asHuman("pr-7", "org-1", EVERYTHING);

		assertThat(tenantClient.read().resource(CodeSystem.class).withId("cs-a").execute()).isNotNull();
		assertThat(clientFor("DEFAULT").read().resource(CodeSystem.class).withId("cs-system").execute()).isNotNull();
	}

	@Test
	void readingAResourceOfAnotherTenant_isForbidden() {
		asHuman("pr-7", "org-1", EVERYTHING);

		assertThatThrownBy(() -> clientFor(OTHER_TENANT).read().resource(Organization.class).withId("org-b").execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void searchingInAnotherTenant_isForbiddenOrEmpty() {
		asHuman("pr-7", "org-1", EVERYTHING);

		try {
			Bundle found = clientFor(OTHER_TENANT).search().forResource(Organization.class)
				.returnBundle(Bundle.class).execute();
			assertThat(found.getEntry()).isEmpty();
		} catch (ForbiddenOperationException expected) {
			// también es un resultado válido
		}
	}

	@Test
	void writingInAnotherTenant_isForbidden() {
		asHuman("pr-7", "org-1", EVERYTHING);
		Organization organization = new Organization();
		organization.setId("org-intruder");
		organization.setName("No debería existir");

		assertThatThrownBy(() -> clientFor(OTHER_TENANT).update().resource(organization).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	/** Hueco B de la exploración: las reglas de lectura de CodeSystem/ValueSet de sistema no llevan forTenantIds. */
	@Test
	void readingTheTerminologyOfAnotherTenant_isForbidden() {
		asHuman("pr-7", "org-1", EVERYTHING);

		assertThatThrownBy(() -> clientFor(OTHER_TENANT).read().resource(CodeSystem.class).withId("cs-b").execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void writingTheTerminologyOfAnotherTenant_isForbidden() {
		asHuman("pr-7", "org-1", EVERYTHING);
		CodeSystem codeSystem = new CodeSystem();
		codeSystem.setId("cs-intruder");
		codeSystem.setUrl("http://farmsuite.test/cs-intruder");
		codeSystem.setStatus(Enumerations.PublicationStatus.ACTIVE);
		codeSystem.setContent(Enumerations.CodeSystemContentMode.COMPLETE);

		assertThatThrownBy(() -> clientFor(OTHER_TENANT).update().resource(codeSystem).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	/** Hueco A de la exploración: un permiso amplio no debe permitir modificar el Practitioner de otro profesional. */
	@Test
	void updatingAnotherPractitioner_isForbidden() {
		asHuman("pr-7", "org-1", EVERYTHING);
		Practitioner other = new Practitioner();
		other.setId("pr-9");
		other.addName().setFamily("Suplantado");

		assertThatThrownBy(() -> tenantClient.update().resource(other).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void deletingAnotherPractitioner_isForbidden() {
		asHuman("pr-7", "org-1", EVERYTHING);

		assertThatThrownBy(() -> tenantClient.delete().resourceById("Practitioner", "pr-9").execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}
}
