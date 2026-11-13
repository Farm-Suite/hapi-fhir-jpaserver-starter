package org.farmsuite.fhir.consent;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.farmsuite.fhir.consent.ConsentMatcher.Access;
import org.farmsuite.fhir.consent.ConsentMatcher.Actor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Consent;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Group;
import org.hl7.fhir.r5.model.Period;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ConsentFinder con los DAO mockeados: la búsqueda en sí es de HAPI, aquí se prueba qué se concluye de ella. */
@SuppressWarnings("unchecked")
class ConsentFinderTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
	private static final String TENANT = "tenant-a";
	private static final Actor PRACTITIONER = new Actor("pr-1", null);
	private static final Actor ORGANIZATION = Actor.ofOrganization("org-1");

	private IFhirResourceDao<Consent> consentDao;
	private IFhirResourceDao<Group> groupDao;
	private ConsentFinder finder;

	@BeforeEach
	void setUp() {
		DaoRegistry registry = mock(DaoRegistry.class);
		consentDao = mock(IFhirResourceDao.class);
		groupDao = mock(IFhirResourceDao.class);
		when(registry.getResourceDao(Consent.class)).thenReturn(consentDao);
		when(registry.getResourceDao(Group.class)).thenReturn(groupDao);
		TenantRequests tenants = mock(TenantRequests.class);
		when(tenants.systemIn(TENANT)).thenReturn(new SystemRequestDetails());
		finder = new ConsentFinder(registry, tenants);
		returnGroups();
	}

	private static IBundleProvider results(List<? extends IBaseResource> resources) {
		IBundleProvider provider = mock(IBundleProvider.class);
		when(provider.getAllResources()).thenReturn((List<IBaseResource>) resources);
		when(provider.getResources(anyInt(), anyInt())).thenAnswer(inv -> {
			int from = inv.getArgument(0);
			return from == 0 ? resources : List.of();
		});
		return provider;
	}

	private void returnConsents(Consent... consents) {
		IBundleProvider provider = results(List.of(consents));
		when(consentDao.search(any(SearchParameterMap.class), any(RequestDetails.class))).thenReturn(provider);
	}

	private void returnGroups(String... ids) {
		List<Group> groups = java.util.Arrays.stream(ids).map(id -> {
			Group g = new Group();
			g.setId("Group/" + id);
			return g;
		}).toList();
		IBundleProvider provider = results(groups);
		when(groupDao.search(any(SearchParameterMap.class), any(RequestDetails.class))).thenReturn(provider);
	}

	private static Consent permit(String granteeRef) {
		Consent consent = new Consent();
		consent.setStatus(Consent.ConsentState.ACTIVE);
		consent.setDecision(Enumerations.ConsentProvisionType.PERMIT);
		consent.addGrantee(new Reference(granteeRef));
		return consent;
	}

	private static Consent withAction(Consent consent, String action) {
		Consent.ProvisionComponent provision = consent.addProvision();
		provision.addAction(new CodeableConcept().addCoding(new Coding().setCode(action)));
		return consent;
	}

	private boolean permits(Actor actor, String type, Access access) {
		return finder.permits(TENANT, "p1", actor, type, access, NOW);
	}

	// --- permits ---

	@Test
	void activePermitToThePractitioner_permits() {
		returnConsents(permit("Practitioner/pr-1"));

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isTrue();
	}

	@Test
	void activePermitToTheOrganization_permits() {
		returnConsents(permit("Organization/org-1"));

		assertThat(permits(ORGANIZATION, "Observation", Access.READ)).isTrue();
	}

	@Test
	void inactiveConsent_doesNotPermit() {
		Consent consent = permit("Practitioner/pr-1");
		consent.setStatus(Consent.ConsentState.INACTIVE);
		returnConsents(consent);

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isFalse();
	}

	@Test
	void expiredConsent_doesNotPermit() {
		Consent consent = permit("Practitioner/pr-1");
		consent.setPeriod(new Period().setEnd(Date.from(NOW.minusSeconds(3600))));
		returnConsents(consent);

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isFalse();
	}

	@Test
	void consentNotYetInForce_doesNotPermit() {
		Consent consent = permit("Practitioner/pr-1");
		consent.setPeriod(new Period().setStart(Date.from(NOW.plusSeconds(3600))));
		returnConsents(consent);

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isFalse();
	}

	@Test
	void consentOfAnotherActor_doesNotPermit() {
		returnConsents(permit("Practitioner/someone-else"), permit("Organization/other-org"));

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isFalse();
		assertThat(permits(ORGANIZATION, "Observation", Access.READ)).isFalse();
	}

	@Test
	void noConsentsFound_doesNotPermit() {
		returnConsents();

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isFalse();
	}

	@Test
	void groupConsent_coversAPatientThatIsAMemberOfTheGroup() {
		returnGroups("g1", "g2");
		returnConsents(permit("Practitioner/pr-1"));

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isTrue();

		ArgumentCaptor<SearchParameterMap> map = ArgumentCaptor.forClass(SearchParameterMap.class);
		verify(consentDao).search(map.capture(), any(RequestDetails.class));
		assertThat(map.getValue().get("subject").toString())
			.contains("Patient/p1").contains("Group/g1").contains("Group/g2");
	}

	@Test
	void withoutGroups_onlyThePatientIsTheSubject() {
		returnConsents();

		permits(PRACTITIONER, "Observation", Access.READ);

		ArgumentCaptor<SearchParameterMap> map = ArgumentCaptor.forClass(SearchParameterMap.class);
		verify(consentDao).search(map.capture(), any(RequestDetails.class));
		assertThat(map.getValue().get("subject").toString()).contains("Patient/p1").doesNotContain("Group/");
	}

	@Test
	void readMode_onlyGivesRead() {
		returnConsents(withAction(permit("Practitioner/pr-1"), "read"));

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isTrue();
		assertThat(permits(PRACTITIONER, "Observation", Access.WRITE)).isFalse();
	}

	@Test
	void noMode_onlyGivesRead() {
		returnConsents(permit("Practitioner/pr-1"));

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isTrue();
		assertThat(permits(PRACTITIONER, "Observation", Access.WRITE)).isFalse();
	}

	@Test
	void writeMode_givesReadAndWrite() {
		returnConsents(withAction(permit("Practitioner/pr-1"), "write"));

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isTrue();
		assertThat(permits(PRACTITIONER, "Observation", Access.WRITE)).isTrue();
	}

	@Test
	void delete_isNeverPermitted() {
		returnConsents(withAction(permit("Practitioner/pr-1"), "write"), permit("Practitioner/pr-1"));

		assertThat(permits(PRACTITIONER, "Observation", Access.DELETE)).isFalse();
	}

	@Test
	void actorWithoutPractitionerNorOrganization_isDeniedWithoutSearching() {
		returnConsents(permit("Practitioner/pr-1"), permit("Organization/org-1"));

		assertThat(permits(new Actor(null, null), "Observation", Access.READ)).isFalse();
		assertThat(permits(new Actor(" ", ""), "Observation", Access.READ)).isFalse();
		org.mockito.Mockito.verifyNoInteractions(consentDao);
	}

	@Test
	void searchFailure_failsClosed() {
		when(consentDao.search(any(SearchParameterMap.class), any(RequestDetails.class))).thenThrow(new InternalErrorException("boom"));

		assertThat(permits(PRACTITIONER, "Observation", Access.READ)).isFalse();
	}

	// --- hasActiveGrant ---

	@Test
	void hasActiveGrant_activeConsentToThePractitioner_isTrue() {
		returnConsents(permit("Practitioner/pr-1"));

		assertThat(finder.hasActiveGrant(TENANT, PRACTITIONER, NOW)).isTrue();
	}

	@Test
	void hasActiveGrant_activeConsentToTheOrganization_isTrue() {
		returnConsents(permit("Organization/org-1"));

		assertThat(finder.hasActiveGrant(TENANT, ORGANIZATION, NOW)).isTrue();
	}

	@Test
	void hasActiveGrant_expiredOrOtherActor_isFalse() {
		Consent expired = permit("Practitioner/pr-1");
		expired.setPeriod(new Period().setEnd(Date.from(NOW.minusSeconds(60))));
		returnConsents(expired, permit("Practitioner/other"));

		assertThat(finder.hasActiveGrant(TENANT, PRACTITIONER, NOW)).isFalse();
	}

	@Test
	void hasActiveGrant_searchesOncePerGrantee() {
		returnConsents();

		finder.hasActiveGrant(TENANT, new Actor("pr-1", "org-1"), NOW);

		verify(consentDao, org.mockito.Mockito.times(2)).search(any(SearchParameterMap.class), any(RequestDetails.class));
	}

	@Test
	void hasActiveGrant_actorWithoutPractitionerNorOrganization_isFalse() {
		returnConsents(permit("Practitioner/pr-1"));

		assertThat(finder.hasActiveGrant(TENANT, new Actor(null, null), NOW)).isFalse();
		org.mockito.Mockito.verifyNoInteractions(consentDao);
	}

	@Test
	void hasActiveGrant_searchFailure_failsClosed() {
		when(consentDao.search(any(SearchParameterMap.class), any(RequestDetails.class))).thenThrow(new InternalErrorException("boom"));

		assertThat(finder.hasActiveGrant(TENANT, PRACTITIONER, NOW)).isFalse();
	}
}
