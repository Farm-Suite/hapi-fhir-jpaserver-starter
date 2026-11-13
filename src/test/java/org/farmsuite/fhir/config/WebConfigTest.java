package org.farmsuite.fhir.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebConfigTest {

	private final WebConfig webConfig = new WebConfig();

	private CorsConfiguration configFor(String... origins) {
		WebConfig.CorsProperties props = new WebConfig.CorsProperties();
		props.setAllowedOrigins(List.of(origins));
		return webConfig.corsConfigurationSource(props)
				.getCorsConfiguration(new MockHttpServletRequest("GET", "/fhir/Patient"));
	}

	@Test
	void withoutConfiguredOriginsNoCorsIsGranted() {
		assertThat(configFor()).isNull();
	}

	@Test
	void onlyConfiguredOriginsAreAllowed() {
		CorsConfiguration config = configFor("https://app.farmsuite.org");

		assertThat(config.checkOrigin("https://app.farmsuite.org")).isEqualTo("https://app.farmsuite.org");
		assertThat(config.checkOrigin("https://evil.example")).isNull();
	}

	@Test
	void allowsTheExpectedMethodsAndHeaders() {
		CorsConfiguration config = configFor("https://app.farmsuite.org");

		assertThat(config.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
		assertThat(config.getAllowedHeaders()).containsExactlyInAnyOrder("Authorization", "Content-Type", "Accept");
		assertThat(config.getAllowCredentials()).isTrue();
		assertThat(config.getMaxAge()).isEqualTo(3600L);
	}

	@Test
	void wildcardReflectsTheRequestOrigin() {
		CorsConfiguration config = configFor("*");

		assertThat(config.checkOrigin("https://any.example")).isEqualTo("https://any.example");
	}
}
