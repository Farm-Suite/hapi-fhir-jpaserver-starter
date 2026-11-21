package org.farmsuite.fhir;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.r5.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El formato del claim {@code permissions} contra un servidor real: {@code *.rscud} da acceso total en el
 * tenant de la sesión y ninguno en otro, los códigos opacos ({@code CONF_READ}) no dan acceso FHIR, patch se
 * ignora y un token sin permisos recibe 403 {@code no-permissions}. Usa sus propias particiones
 * ({@code perm-a}, {@code perm-b}).
 */
class PermissionFormatIT extends AbstractFarmSuiteServerIT {

	private static final String TENANT_A = "perm-a";
	private static final String TENANT_B = "perm-b";
	private static final String JSON_PATCH = "[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"Parcheada\"}]";
	private static boolean setUp;

	@BeforeEach
	void createTenants() {
		if (setUp) return;
		asServiceAccount("pr-admin");
		createPartition(13, TENANT_A);
		createPartition(14, TENANT_B);
		clientFor(TENANT_A).update().resource(organization("perm-org-a", "Org A")).execute();
		clientFor(TENANT_B).update().resource(organization("perm-org-b", "Org B")).execute();
		setUp = true;
	}

	private static Organization organization(String id, String name) {
		Organization organization = new Organization();
		organization.setId(id);
		organization.setName(name);
		return organization;
	}

	@Test
	void everythingRscud_givesFullAccessInTheSessionTenant() {
		asHumanOf(TENANT_A, "pr-7", null, List.of("*.rscud"));

		assertThat(clientFor(TENANT_A).read().resource(Organization.class).withId("perm-org-a").execute()).isNotNull();
		// El id lo asigna el servidor: se crea sin id y se borra por el id devuelto.
		Organization created = new Organization();
		created.setName("Nueva");
		var id = clientFor(TENANT_A).create().resource(created).execute().getId();
		clientFor(TENANT_A).delete().resourceById(id.toUnqualifiedVersionless()).execute();
	}

	@Test
	void everythingRscud_givesNoAccessInAnotherTenant() {
		asHumanOf(TENANT_A, "pr-7", null, List.of("*.rscud"));

		assertThatThrownBy(() -> clientFor(TENANT_B).read().resource(Organization.class).withId("perm-org-b").execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> clientFor(TENANT_B).update().resource(organization("perm-org-intruder", "No")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> clientFor(TENANT_B).delete().resourceById("Organization", "perm-org-b").execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void anOpaqueCode_givesNoFhirAccess() {
		asHumanOf(TENANT_A, "pr-7", null, List.of("CONF_READ"));

		assertThatThrownBy(() -> clientFor(TENANT_A).read().resource(Organization.class).withId("perm-org-a").execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> clientFor(TENANT_A).update().resource(organization("perm-org-opaque", "No")).execute())
			.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void patch_isIgnored() {
		// Con solo "p" no se concede nada: ni siquiera leer.
		asHumanOf(TENANT_A, "pr-7", null, List.of("Organization.p"));
		assertThatThrownBy(() -> clientFor(TENANT_A).patch().withBody(JSON_PATCH).withId("Organization/perm-org-a").execute())
			.isInstanceOf(ForbiddenOperationException.class);
		assertThatThrownBy(() -> clientFor(TENANT_A).read().resource(Organization.class).withId("perm-org-a").execute())
			.isInstanceOf(ForbiddenOperationException.class);

		// Con "rscudp" la "p" no añade ni quita nada: se conceden las demás acciones.
		asHumanOf(TENANT_A, "pr-7", null, List.of("Organization.rscudp"));
		assertThat(clientFor(TENANT_A).read().resource(Organization.class).withId("perm-org-a").execute()).isNotNull();
	}

	@Test
	void aTokenWithoutPermissionClaims_gets403NoPermissions() {
		asHumanOf(TENANT_A, "pr-7", null, List.of());

		assertThatThrownBy(() -> clientFor(TENANT_A).read().resource(Organization.class).withId("perm-org-a").execute())
			.isInstanceOf(ForbiddenOperationException.class)
			.hasMessageContaining("no-permissions");
	}
}
