package org.farmsuite.fhir;

import org.farmsuite.sso.oidc.OidcProperties;
import org.farmsuite.sso.session.IntrospectionConverterUserSessionModel;
import org.farmsuite.sso.session.JwtConverterUserSessionModel;
import org.farmsuite.sso.session.UserSessionRepository;
import org.farmsuite.sso.session.UserSessionSpringSecurityKeycloak;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.introspection.NimbusOpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;

@Configuration
@EnableConfigurationProperties(OidcProperties.class)
public class FhirSecurityConfig {

	private static final String[] PUBLIC_PATHS = {
		/*
		"/fhir/metadata",
		"/fhir/.well-known/**",
		"/actuator/health/**"
		 */
	};

	@Bean
	public UserSessionRepository fhirUserSessionRepository() {
		return new UserSessionSpringSecurityKeycloak();
	}

	@Bean
	public SecurityFilterChain fhirSecurityFilterChain(HttpSecurity http, OidcProperties props) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(PUBLIC_PATHS).permitAll()
				.anyRequest().authenticated()
			);

		if (props.getMode() == OidcProperties.Mode.INTROSPECT) {
			http.oauth2ResourceServer(oauth2 -> oauth2
				.opaqueToken(opaque -> opaque
					.introspector(new NimbusOpaqueTokenIntrospector(
						props.getIntrospectionUri(),
						props.getClientId(),
						props.getClientSecret()
					))
					.authenticationConverter(new IntrospectionConverterUserSessionModel())
				)
			);
		} else {
			http.oauth2ResourceServer(oauth2 -> oauth2
				.jwt(jwt -> jwt
					.decoder(buildJwtDecoder(props))
					.jwtAuthenticationConverter(new JwtConverterUserSessionModel())
				)
			);
		}

		return http.build();
	}

	private JwtDecoder buildJwtDecoder(OidcProperties props) {
		if (props.getMode() == OidcProperties.Mode.LOCAL_CERT) {
			return NimbusJwtDecoder.withPublicKey(loadPublicKey(props.getPublicKeyLocation())).build();
		}
		// LOCAL_JWKS
		if (StringUtils.hasText(props.getJwksUri())) {
			return NimbusJwtDecoder.withJwkSetUri(props.getJwksUri()).build();
		}
		return JwtDecoders.fromIssuerLocation(props.getIssuerUri());
	}

	private RSAPublicKey loadPublicKey(String location) {
		Resource resource = new DefaultResourceLoader().getResource(location);
		try (InputStream is = resource.getInputStream()) {
			return RsaKeyConverters.x509().convert(is);
		} catch (IOException e) {
			throw new IllegalStateException("No se pudo cargar la clave pública RSA desde: " + location, e);
		}
	}
}
