package org.farmsuite.fhir.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.farmsuite.sso.client.ClientCredentials;
import org.farmsuite.sso.client.OAuth2ClientCredentialsClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Beans para que este servidor FHIR llame de vuelta a Administration-MIC
 * (el interceptor de sincronización de {@code Task.owner}, ver
 * {@link org.farmsuite.fhir.usecase.TaskOwnerSyncNotify}) autenticado como
 * cuenta de servicio — la misma cuenta ({@code service-account}) que ya usa
 * {@code sso-keycloak} vía {@code AdministrationClientsFactory} en la
 * dirección opuesta (SSO -> Administration-MIC), mismo mecanismo
 * (client_credentials, rol de realm {@code SERVICE_ACCOUNT_FARMSUITE} ya
 * mapeado a admin en Administration-MIC).
 */
@Configuration
@EnableConfigurationProperties(AdministrationClientConfig.AdministrationClientProperties.class)
@RequiredArgsConstructor
public class AdministrationClientConfig {

    private final AdministrationClientProperties properties;

    @ConfigurationProperties(prefix = "farmsuite.administration")
    @Getter
    @Setter
    public static class AdministrationClientProperties {
        private String baseUrl = "https://farmsuite.org/administration/api";
        private String tokenEndpoint = "https://farmsuite.org/auth/realms/farmsuite/protocol/openid-connect/token";
        private String clientId = "service-account";
        private String clientSecret;
    }

    @Bean
    public OAuth2ClientCredentialsClient administrationTokenClient() {
        return new OAuth2ClientCredentialsClient(
                new ClientCredentials(properties.getTokenEndpoint(), properties.getClientId(), properties.getClientSecret(), "openid"),
                Duration.ofMinutes(10)
        );
    }

    @Bean
    public RestClient administrationRestClient() {
        return RestClient.create();
    }
}
