package org.farmsuite.fhir.consent;

import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.farmsuite.fhir.consent.TenantAccessService.Kind;
import org.farmsuite.sso.session.UserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cómo entra al tenant de la URL quien hace la petición: por su sesión, por un Consent, o no entra. */
class TenantAccessServiceTest {

	private UserSessionRepository sessions;
	private ConsentFinder consents;
	private TenantAccessService service;

	@BeforeEach
	void setUp() {
		sessions = mock(UserSessionRepository.class);
		consents = mock(ConsentFinder.class);
		service = new TenantAccessService(sessions, consents);
	}

	@Test
	void theTenantOfTheSession_isOwnTenant_withoutLookingForConsents() {
		withSession("farm-a", true);

		assertThat(service.resolve(request("farm-a"))).isEqualTo(Kind.OWN_TENANT);
		verify(consents, never()).hasActiveGrant(any(), any(), any());
	}

	@Test
	void anotherTenant_withAnActiveConsentForThePractitionerOrTheirOrganization_isByConsent() {
		withSession("farm-a", false);
		when(consents.hasActiveGrant(eq("farm-b"), eq(new ConsentMatcher.Actor("dr-1", "org-1")), any())).thenReturn(true);

		assertThat(service.resolve(request("farm-b"))).isEqualTo(Kind.BY_CONSENT);
	}

	@Test
	void anotherTenant_withoutAConsent_isNone() {
		withSession("farm-a", false);

		assertThat(service.resolve(request("farm-b"))).isEqualTo(Kind.NONE);
	}

	@Test
	void theAnswerIsComputedOncePerRequest() {
		withSession("farm-a", false);
		ServletRequestDetails request = request("farm-b");

		service.resolve(request);
		service.resolve(request);

		verify(consents, times(1)).hasActiveGrant(eq("farm-b"), any(), any());
	}

	@Test
	void theCheckDoesNotApplyToTheServiceAccount_internalCalls_noTenant_default_orNoSession() {
		when(sessions.isAdmin()).thenReturn(true);
		assertThat(service.resolve(request("farm-b"))).isEqualTo(Kind.NOT_SCOPED);

		when(sessions.isAdmin()).thenReturn(false);
		assertThat(service.resolve(new SystemRequestDetails())).isEqualTo(Kind.NOT_SCOPED);
		assertThat(service.resolve(null)).isEqualTo(Kind.NOT_SCOPED);

		withSession("farm-a", false);
		assertThat(service.resolve(request(null))).isEqualTo(Kind.NOT_SCOPED);
		assertThat(service.resolve(request(" "))).isEqualTo(Kind.NOT_SCOPED);
		assertThat(service.resolve(request("default"))).isEqualTo(Kind.NOT_SCOPED);

		when(sessions.getUserSession()).thenReturn(Optional.empty());
		assertThat(service.resolve(request("farm-b"))).isEqualTo(Kind.NOT_SCOPED);
		verify(consents, never()).hasActiveGrant(any(), any(), any());
	}

	private void withSession(String ownTenant, boolean canAccessRequested) {
		UserSessionModel session = mock(UserSessionModel.class);
		when(session.getPractitionerId()).thenReturn("dr-1");
		when(session.getOrganizationId()).thenReturn("org-1");
		when(session.getTenantId()).thenReturn(ownTenant);
		when(session.canAccessTenant(any())).thenReturn(canAccessRequested);
		when(sessions.isAdmin()).thenReturn(false);
		when(sessions.getUserSession()).thenReturn(Optional.of(session));
	}

	private static ServletRequestDetails request(String tenant) {
		ServletRequestDetails details = new ServletRequestDetails();
		details.setTenantId(tenant);
		return details;
	}
}
