package org.farmsuite.fhir.config;

import org.farmsuite.sso.client.OAuth2ClientCredentialsClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdministrationClientConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(AdministrationClientConfig.class);

	@Test
	void bindsBaseUrlAndServiceAccountCredentialsFromProperties() {
		runner.withPropertyValues(
				"farmsuite.administration.base-url=http://admin.local/api",
				"farmsuite.administration.token-endpoint=http://sso.local/token",
				"farmsuite.administration.client-id=my-client",
				"farmsuite.administration.client-secret=s3cret",
				"farmsuite.administration.timeout=2s"
		).run(ctx -> {
			AdministrationClientConfig.AdministrationClientProperties props =
					ctx.getBean(AdministrationClientConfig.AdministrationClientProperties.class);
			assertThat(props.getBaseUrl()).isEqualTo("http://admin.local/api");
			assertThat(props.getTokenEndpoint()).isEqualTo("http://sso.local/token");
			assertThat(props.getClientId()).isEqualTo("my-client");
			assertThat(props.getClientSecret()).isEqualTo("s3cret");
			assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(2));
			assertThat(ctx).hasSingleBean(OAuth2ClientCredentialsClient.class).hasSingleBean(RestClient.class);
		});
	}

	@Test
	void usesTheServiceAccountDefaultsWhenNothingIsConfigured() {
		runner.run(ctx -> {
			AdministrationClientConfig.AdministrationClientProperties props =
					ctx.getBean(AdministrationClientConfig.AdministrationClientProperties.class);
			assertThat(ctx).hasNotFailed();
			assertThat(props.getClientId()).isEqualTo("service-account");
			assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(5));
		});
	}
}
