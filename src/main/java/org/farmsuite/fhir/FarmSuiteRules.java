package org.farmsuite.fhir;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.farmsuite.sso.session.UserSessionRepository;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class FarmSuiteRules extends AuthorizationInterceptor {

	private final UserSessionRepository userSessionRepository;


	@Override
	public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
		if (userSessionRepository.isAdmin()){
			log.debug("Admin user logged in");
			return new RuleBuilder()
				.allowAll()
				.build();
		}




		/*
		return userSessionRepository.getUserSession()
			.map(this::buildRulesForSession)
			.orElseGet(() -> new RuleBuilder().denyAll("no-session").build());
		 */
		return new RuleBuilder().denyAll("no-session").build();
	}
}
