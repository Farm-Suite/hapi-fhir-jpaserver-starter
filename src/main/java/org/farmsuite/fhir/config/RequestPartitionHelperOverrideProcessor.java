package org.farmsuite.fhir.config;

import lombok.extern.slf4j.Slf4j;
import org.farmsuite.fhir.interceptors.RequestPartitionableResourcesHelper;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.stereotype.Component;

/**
 * Reemplaza en runtime el bean {@code requestPartitionHelperService} estándar de HAPI
 * ({@code ca.uhn.fhir.jpa.config.JpaConfig}) por {@link RequestPartitionableResourcesHelper}.
 *
 * <p>Antes se intentaba con un {@code @Bean} de nombre distinto + {@code @Primary} en
 * {@code WebConfig} — no funcionaba: con
 * {@code spring.main.allow-bean-definition-overriding=true}, un choque de bean con el MISMO
 * nombre se resuelve por "el que se registra último gana", no por {@code @Primary} (que solo
 * desempata entre bean *distintos* del mismo tipo). Depender de qué {@code @Configuration} se
 * procesa último es frágil.
 *
 * <p>Este enfoque es determinístico independientemente del orden de escaneo:
 * {@code ConfigurationClassPostProcessor} (el que expande {@code @Bean} de cualquier
 * {@code @Configuration}, incluida {@code JpaConfig}) es siempre el primer
 * {@code BeanDefinitionRegistryPostProcessor} en ejecutarse (implementa
 * {@code PriorityOrdered}) — así que cuando este bean (un {@code @Component} plano, sin
 * {@code PriorityOrdered}/{@code Ordered}) se ejecuta, la bean definition
 * {@code requestPartitionHelperService} de {@code JpaConfig} ya existe siempre en el registro,
 * sin importar en qué orden Spring haya escaneado los paquetes.
 */
@Slf4j
@Component
public class RequestPartitionHelperOverrideProcessor implements BeanDefinitionRegistryPostProcessor {

	private static final String BEAN_NAME = "requestPartitionHelperService";

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		if (!registry.containsBeanDefinition(BEAN_NAME)) {
			log.warn("Bean '{}' no encontrado — ¿cambió el nombre en HAPI FHIR? "
				+ "RequestPartitionableResourcesHelper no queda activo.", BEAN_NAME);
			return;
		}

		registry.removeBeanDefinition(BEAN_NAME);
		registry.registerBeanDefinition(BEAN_NAME, new RootBeanDefinition(RequestPartitionableResourcesHelper.class));
		log.info("Bean '{}' reemplazado por {}", BEAN_NAME, RequestPartitionableResourcesHelper.class.getSimpleName());
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		// No-op — el trabajo real es en postProcessBeanDefinitionRegistry, antes de que se
		// instancie ningún bean.
	}
}
