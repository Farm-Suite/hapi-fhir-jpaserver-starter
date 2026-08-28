package org.farmsuite.fhir.config;

import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import org.farmsuite.fhir.interceptors.RequestPartitionableResourcesHelper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Registra el bean {@code corsConfigurationSource} que Spring Security busca
 * cuando {@code sso-interceptor} activa {@code .cors(Customizer.withDefaults())}
 * (ver {@code OidcSecurityConfiguration} en SSO/sso-interceptor). Sin este bean,
 * el filtro CORS de Spring Security rechaza cualquier preflight con
 * "403 Invalid CORS request" antes de que la petición llegue al servlet FHIR
 * — el interceptor CORS propio de HAPI (hapi.fhir.cors.allowed_origin) nunca
 * llega a ejecutarse, porque corre aguas abajo del filtro de Spring Security.
 * Mismo patrón que WebConfig en Administration-MIC/Configuration-MIC.
 */
@Configuration
@EnableConfigurationProperties(WebConfig.CorsProperties.class)
public class WebConfig {

	@ConfigurationProperties(prefix = "cors")
	public static class CorsProperties {
		private List<String> allowedOrigins = new ArrayList<>();

		public List<String> getAllowedOrigins() {
			return allowedOrigins;
		}

		public void setAllowedOrigins(List<String> allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
		if (props.getAllowedOrigins().isEmpty()) {
			return new UrlBasedCorsConfigurationSource();
		}

		CorsConfiguration config = new CorsConfiguration();
		if (props.getAllowedOrigins().contains("*")) {
			// setAllowedOrigins(List.of("*")) + allowCredentials(true) lanza
			// IllegalArgumentException en cada request (Spring no permite el
			// valor literal "*" junto con credenciales). allowedOriginPatterns
			// sí soporta "*" reflejando el Origin real en la respuesta.
			config.setAllowedOriginPatterns(List.of("*"));
		} else {
			config.setAllowedOrigins(props.getAllowedOrigins());
		}
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}


	// Nombre de bean distinto a propósito: el @Bean estándar de HAPI en
	// ca.uhn.fhir.jpa.config.JpaConfig se llama igual (requestPartitionHelperService,
	// por convención el nombre del método). Con
	// spring.main.allow-bean-definition-overriding=true, un choque de nombres se
	// resuelve por "el que se registra último gana" — NO por @Primary (@Primary solo
	// desempata entre beans con nombres DISTINTOS del mismo tipo). Con el mismo
	// nombre, JpaConfig se procesa después y pisa esta definición en silencio, así
	// que en runtime queda activo el RequestPartitionHelperSvc estándar de HAPI y
	// este @Primary nunca llega a evaluarse.
	@Bean
	@Primary
	public IRequestPartitionHelperSvc farmSuiteRequestPartitionHelperService() {
		return new RequestPartitionableResourcesHelper();
	}
}
