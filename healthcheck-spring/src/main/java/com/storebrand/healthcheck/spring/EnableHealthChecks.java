/*
 * Copyright 2022 Storebrand ASA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.storebrand.healthcheck.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import com.storebrand.healthcheck.annotation.HealthCheck;
import com.storebrand.healthcheck.impl.ServiceInfo;

/**
 * Annotation for enabling health checks in Spring. Add this annotation to a Spring configuration class, and watch as
 * beans with methods annotated with {@link HealthCheck} are automatically registered in the {@link
 * com.storebrand.healthcheck.HealthCheckRegistry}.
 * <p>
 * This annotation has {@link #projectName()} and {@link #projectVersion()} that can be set statically for the health
 * check system. As an alternative you can leave them empty and provide a spring bean that implements {@link
 * HealthCheckSettings} for providing these settings. You can use the {@link SimpleHealthCheckSettings} implementation.
 * <p>
 * An implementation of both {@link com.storebrand.healthcheck.HealthCheckRegistry} and {@link
 * ServiceInfo} will be provided to the Spring context by this bean.
 *
 * @see HealthCheckSpringAnnotationRegistration for details on automatic registration of health checks.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({ HealthCheckSpringAnnotationRegistration.class, EnableHealthChecks.HealthCheckBeanRegistration.class })
public @interface EnableHealthChecks {

    String projectName() default "";

    String projectVersion() default "";

    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    class HealthCheckBeanRegistration implements ImportBeanDefinitionRegistrar {
        private static final Logger log = LoggerFactory.getLogger(HealthCheckBeanRegistration.class);

        @Override
        public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                BeanDefinitionRegistry registry) {

            AnnotationAttributes annotationAttributes = (AnnotationAttributes) importingClassMetadata
                    .getAnnotationAttributes(EnableHealthChecks.class.getName());
            if (annotationAttributes != null) {
                String projectName = annotationAttributes.getString("projectName");
                String projectVersion = annotationAttributes.getString("projectVersion");

                if (!"".equals(projectName) && projectName != null) {
                    log.info("Found EnableHealthCheck annotation with project name [" + projectName
                            + "] and version [" + projectVersion + "]");

                    BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(
                                    SimpleHealthCheckSettings.class)
                            .addConstructorArgValue(projectName)
                            .addConstructorArgValue(projectVersion)
                            .setScope(BeanDefinition.SCOPE_SINGLETON);

                    BeanDefinition settingsBeanDefinition = builder.getBeanDefinition();

                    registry.registerBeanDefinition(SimpleHealthCheckSettings.class.getSimpleName(),
                            settingsBeanDefinition);
                }
            }

            registerSingleton(registry, ServiceInfoFactory.class);
            registerSingleton(registry, HealthCheckRegistryFactory.class);
        }

        private void registerSingleton(BeanDefinitionRegistry registry, Class<?> clazz) {
            BeanDefinition beanDefinition = BeanDefinitionBuilder
                    .genericBeanDefinition(clazz)
                    .setScope(BeanDefinition.SCOPE_SINGLETON)
                    .getBeanDefinition();
            registry.registerBeanDefinition(clazz.getSimpleName(), beanDefinition);
        }
    }
}
