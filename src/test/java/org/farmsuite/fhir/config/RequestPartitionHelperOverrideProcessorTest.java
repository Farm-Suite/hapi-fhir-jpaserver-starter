package org.farmsuite.fhir.config;

import org.farmsuite.fhir.interceptors.RequestPartitionableResourcesHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPartitionHelperOverrideProcessorTest {

	private static final String BEAN_NAME = "requestPartitionHelperService";

	private final RequestPartitionHelperOverrideProcessor processor = new RequestPartitionHelperOverrideProcessor();

	@Test
	void replacesTheStandardHelperDefinitionWithTheOwnOne() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		registry.registerBeanDefinition(BEAN_NAME, new RootBeanDefinition(Object.class));

		processor.postProcessBeanDefinitionRegistry(registry);

		assertThat(registry.getBeanDefinition(BEAN_NAME).getBeanClassName())
				.isEqualTo(RequestPartitionableResourcesHelper.class.getName());
		assertThat(registry.getBeanDefinitionCount()).isEqualTo(1);
	}

	@Test
	void registersNothingWhenTheStandardBeanIsMissing() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

		processor.postProcessBeanDefinitionRegistry(registry);

		assertThat(registry.containsBeanDefinition(BEAN_NAME)).isFalse();
	}
}
