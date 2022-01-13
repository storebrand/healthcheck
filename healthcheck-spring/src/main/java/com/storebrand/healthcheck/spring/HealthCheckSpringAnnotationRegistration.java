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

import static java.util.stream.Collectors.toList;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Role;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

import com.storebrand.healthcheck.annotation.CombinedInstanceResolver;
import com.storebrand.healthcheck.annotation.HealthCheck;
import com.storebrand.healthcheck.annotation.HealthCheckAnnotationUtils;
import com.storebrand.healthcheck.annotation.HealthCheckInstanceResolver;
import com.storebrand.healthcheck.annotation.HealthCheckMethodScanner;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.annotation.SimpleInstanceResolver;

/**
 * Add this bean to Spring in order to automatically register all {@link HealthCheck} annotated methods in beans.
 * <p>
 * This requires an implementation of {@link HealthCheckRegistry} to be present in the Spring context. The easiest way
 * to get that is to use {@link EnableHealthChecks}. That will also import this bean.
 * <p>
 * It is also possible to supply additional scanners that scans for annotated methods by implementing {@link
 * HealthCheckMethodScanner} and adding it to the Spring context.
 * <p>
 * If you want control over instance creation it is possible to add an implementation of {@link
 * HealthCheckInstanceResolver} to the Spring context. The default instance resolver will use a {@link
 * CombinedInstanceResolver} that first attempts to resolve using {@link SpringInstanceResolver}, and then moves on to
 * {@link SimpleInstanceResolver} if a bean is not found for the given annotated method.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class HealthCheckSpringAnnotationRegistration implements BeanPostProcessor,
        ApplicationContextAware {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckSpringAnnotationRegistration.class);
    private static final String LOG_PREFIX = "#SPRINGHEALTHCHECK# ";

    private final Set<Class<?>> _classesThatHaveBeenChecked = ConcurrentHashMap.newKeySet();
    private final Set<Method> _methodsWithHealthCheckAnnotations = ConcurrentHashMap.newKeySet();

    private ConfigurableApplicationContext _configurableApplicationContext;
    private ConfigurableListableBeanFactory _configurableListableBeanFactory;
    private HealthCheckInstanceResolver _instanceResolver;

    private HealthCheckRegistry _healthCheckRegistry;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            throw new IllegalStateException("The ApplicationContext when using HealthCheck's SpringConfig"
                    + " must implement " + ConfigurableApplicationContext.class.getSimpleName()
                    + ", while the provided ApplicationContext is of type [" + applicationContext.getClass().getName()
                    + "], and evidently don't.");
        }
        _configurableApplicationContext = (ConfigurableApplicationContext) applicationContext;

        // NOTICE!! We CAN NOT touch the _beans_ at this point, since we then will create them, and we will therefore
        // be hit by the "<bean> is not eligible for getting processed by all BeanPostProcessors" - the
        // BeanPostProcessor in question being ourselves!
        // (.. However, the BeanDefinitions is okay to handle.)
        _configurableListableBeanFactory = _configurableApplicationContext.getBeanFactory();
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // No need to do anything before beans are initialized
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // :: Get the BeanDefinition, to check for type.
        BeanDefinition beanDefinition;
        try {
            beanDefinition = _configurableListableBeanFactory.getBeanDefinition(beanName);
        }
        catch (NoSuchBeanDefinitionException e) {
            // -> This is a non-registered bean, which evidently is used when doing unit tests with JUnit SpringRunner.
            log.info(LOG_PREFIX + getClass().getSimpleName()
                    + ".postProcessAfterInitialization(bean, \"" + beanName
                    + "\"): Found no bean definition for the given bean name! Test class?! Ignoring.");
            return bean;
        }

        Class<?> targetClass = ClassUtils.getUserClass(bean);
        // ?: Have we checked this bean before? (might happen with prototype beans)
        if (_classesThatHaveBeenChecked.contains(targetClass)) {
            // -> Yes, we've checked it before, and it either has no @HealthCheck-annotations, or we have already
            // registered the methods with annotations.
            return bean;
        }

        // E-> must check this bean.
        List<Method> methodsWithHealthCheckAnnotation = Arrays.stream(targetClass.getMethods())
                .filter(method -> AnnotationUtils.findAnnotation(method, HealthCheck.class) != null)
                .collect(toList());
        // ?: Are there no annotated methods?
        if (methodsWithHealthCheckAnnotation.isEmpty()) {
            // -> There are no @HealthCheck annotations, add it to list of checked classes, and return bean
            _classesThatHaveBeenChecked.add(targetClass);
            return bean;
        }

        // Assert that it is a singleton. NOTICE: It may be prototype, but also other scopes like request-scoped.
        if (!beanDefinition.isSingleton()) {
            throw new BeanCreationException("The bean [" + beanName + "] is not a singleton (scope: ["
                    + beanDefinition.getScope() + "]), which does not make sense when it comes to beans that have"
                    + " methods annotated with @HealthCheck-annotations.");
        }

        for (Method method : methodsWithHealthCheckAnnotation) {
            if (!HealthCheckAnnotationUtils.isValidHealthCheckMethod(method)) {
                throw new BeanCreationException("The bean [" + beanName + "] contains an invalid @HealthCheck method: ["
                        + method + "]. Method should return void, and have one argument of type CheckSpecification.");
            }
        }

        log.info(LOG_PREFIX + "Found class " + targetClass.getSimpleName() + " with "
                + methodsWithHealthCheckAnnotation.size()
                + " @HealthCheck methods.");

        // ?: Do we have a HealthCheckRegistry object already?
        if (_healthCheckRegistry != null) {
            // -> Yes, this means that the context has been refreshed, and we have a reference to the registry.
            // We can go ahead and register the health check immediately.
            methodsWithHealthCheckAnnotation.forEach(this::registerHealthCheckMethod);
        }
        else {
            // -> No, the context has not been refreshed yet. We store for registration for after context refreshed.
            _methodsWithHealthCheckAnnotations.addAll(methodsWithHealthCheckAnnotation);
        }

        _classesThatHaveBeenChecked.add(targetClass);

        return bean;
    }


    private void registerHealthCheckMethod(Method method) {
        if (_healthCheckRegistry.isMethodRegistered(method)) {
            log.warn("Trying to register method [" + method + "] more than once.");
            return;
        }
        HealthCheckAnnotationUtils.registerAnnotatedMethod(method, _healthCheckRegistry, _instanceResolver);
    }

    @EventListener
    public void onContextRefreshedEvent(ContextRefreshedEvent ev) {
        HealthCheckRegistry healthCheckRegistry = _configurableApplicationContext.getBean(HealthCheckRegistry.class);

        _healthCheckRegistry = healthCheckRegistry;

        try {
            _instanceResolver = _configurableApplicationContext.getBean(HealthCheckInstanceResolver.class);
        }
        catch (NoSuchBeanDefinitionException ex) {
            log.info("No HealthCheckInstanceResolver bean found in Spring - creating default instance resolver.");
            _instanceResolver = CombinedInstanceResolver.of(
                    new SpringInstanceResolver(_configurableApplicationContext),
                    new SimpleInstanceResolver());
        }

        Map<String, HealthCheckMethodScanner> scannerBeans =
                _configurableApplicationContext.getBeansOfType(HealthCheckMethodScanner.class);

        for (HealthCheckMethodScanner scannerBean : scannerBeans.values()) {
            log.info("Found HealthCheckScanner [" + scannerBean.getClass().getName() + "]");
            Set<Method> methods = scannerBean.getHealthCheckAnnotatedMethods();
            log.info(" - scanner found " + methods.size() + " @HealthCheck annotated methods.");
            _methodsWithHealthCheckAnnotations.addAll(methods);
        }

        for (Method method : _methodsWithHealthCheckAnnotations) {
            registerHealthCheckMethod(method);
        }
        _methodsWithHealthCheckAnnotations.clear();

        healthCheckRegistry.startHealthChecks();
    }

}
