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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.HealthCheckRegistry.CreateReportRequest;
import com.storebrand.healthcheck.HealthCheckRegistry.RegisteredHealthCheck;
import com.storebrand.healthcheck.HealthCheckReportDto;
import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.Responsible;
import com.storebrand.healthcheck.ServiceInfo;
import com.storebrand.healthcheck.scanner.ClassGraphHealthCheckMethodScanner;
import com.storebrand.healthcheck.spring.testhealthchecks1.SpringComponentWithHealthCheck;
import com.storebrand.healthcheck.test.HealthCheckAssertions;

/**
 * Tests that verify correct behaviour for startup, registration and shutdown of health checks in Spring.
 */
public class HealthCheckSpringTest {

    /**
     * Test that starts and shuts down, and verifies status of the health check system after startup and shutdown.
     */
    @Test
    public void testStartupAndShutdownWithoutAnyHealthChecks() {
        HealthCheckRegistry healthCheckRegistry;

        // :: Create Spring context with try-with-resources for auto closing
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Register test configuration for health checks
            context.register(HealthCheckSpringTestConfiguration.class);

            // Refresh context
            context.refresh();

            // At this point we should have a HealthCheckRegistry and a ServiceInfo bean available
            ServiceInfo serviceInfo = context.getBean(ServiceInfo.class);
            assertNotNull(serviceInfo);
            healthCheckRegistry = context.getBean(HealthCheckRegistry.class);
            assertNotNull(healthCheckRegistry);

            // The health check registry should be running, and have no health checks
            assertTrue(healthCheckRegistry.isRunning());
            assertEquals(0, healthCheckRegistry.getRegisteredHealthChecks().size());

        }
        // At this point the Spring context is closed, and we should be able to verify that the HealthCheckRegistry is
        // not running anymore.
        assertFalse(healthCheckRegistry.isRunning());

        // We should not be able to start the HealthCheckRegistry again, as it has been shutdown.
        assertThrows(IllegalStateException.class, healthCheckRegistry::startHealthChecks);
    }

    /**
     * Tests that the health checks can be registered directly.
     */
    @Test
    public void testRegisteringAHealthCheckDirectly() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(HealthCheckSpringTestConfiguration.class);
            context.refresh();

            // Register a health check
            // When registering health checks programmatically like this you should probably do it inside a
            // @PostConstruct or similar places, that run before the context is refreshed. It is also possible to
            // register after context refresh, but checks that are used for startup might be missed by monitoring
            // systems if they are registered after context refresh.
            HealthCheckRegistry healthCheckRegistry = context.getBean(HealthCheckRegistry.class);
            healthCheckRegistry.registerHealthCheck(HealthCheckMetadata.createSynchronous("Simple test"),
                    specs -> specs.check(Responsible.DEVELOPERS, Axis.of(Axis.NOT_READY), ctx -> ctx.ok("OK")));

            // Verify that the health check is registered and running
            assertEquals(1, healthCheckRegistry.getRegisteredHealthChecks().size());
            RegisteredHealthCheck registeredHealthCheck = healthCheckRegistry.getRegisteredHealthChecks().get(0);
            assertEquals("Simple test", registeredHealthCheck.getMetadata().name);
            assertTrue(registeredHealthCheck.getMetadata().sync);
            assertFalse(registeredHealthCheck.getMetadata().async);
            assertTrue(registeredHealthCheck.isRunning());

            // Get a report and check that it is as expected
            HealthCheckReportDto report = healthCheckRegistry.createReport(new CreateReportRequest());
            assertEquals(1, report.healthChecks.size());
            HealthCheckDto checkDto = report.healthChecks.get(0);
            assertEquals("Simple test", checkDto.name);
            assertEquals(1, checkDto.axes.specified.size());
            assertEquals(0, checkDto.axes.activated.size());
            assertFalse(checkDto.axes.activated.contains(Axis.NOT_READY));
            assertTrue(checkDto.axes.specified.contains(Axis.NOT_READY));
        }
    }

    /**
     * Tests that the health checks in Spring beans are registered automatically.
     */
    @Test
    public void testAutomaticRegistrationOfHealthChecksOnBeans() {
        SpringComponentWithHealthCheck springComponentWithHealthCheck;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(HealthCheckSpringTestConfiguration.class);
            // Register the bean with a @HealthCheck annotated method
            context.register(SpringComponentWithHealthCheck.class);
            context.refresh();
            HealthCheckRegistry healthCheckRegistry = context.getBean(HealthCheckRegistry.class);

            // Verify that the health check is registered and running
            assertEquals(1, healthCheckRegistry.getRegisteredHealthChecks().size());
            RegisteredHealthCheck registeredHealthCheck = healthCheckRegistry.getRegisteredHealthChecks().get(0);
            assertEquals("Spring component health check", registeredHealthCheck.getMetadata().name);
            assertTrue(registeredHealthCheck.getMetadata().sync);
            assertFalse(registeredHealthCheck.getMetadata().async);
            assertTrue(registeredHealthCheck.isRunning());

            // Get a report and check that it is as expected
            HealthCheckReportDto report = healthCheckRegistry.createReport(new CreateReportRequest());
            assertEquals(1, report.healthChecks.size());
            HealthCheckDto checkDto = report.healthChecks.get(0);
            assertEquals("Spring component health check", checkDto.name);
            assertEquals(1, report.axes.specified.size());
            assertEquals(0, report.axes.activated.size());
            assertTrue(report.axes.specified.contains(Axis.DEGRADED_MINOR));
            assertEquals(1, checkDto.axes.specified.size());
            assertEquals(0, checkDto.axes.activated.size());
            assertTrue(checkDto.axes.specified.contains(Axis.DEGRADED_MINOR));

            // Modify outcome of check, and verify that axis is triggered
            springComponentWithHealthCheck = context.getBean(SpringComponentWithHealthCheck.class);
            springComponentWithHealthCheck.setFailCheck(true);

            HealthCheckReportDto failingReport = healthCheckRegistry.createReport(new CreateReportRequest());
            HealthCheckDto failingCheckDto = failingReport.healthChecks.get(0);
            assertEquals(1, failingReport.axes.specified.size());
            assertEquals(1, failingReport.axes.activated.size());
            assertTrue(failingReport.axes.specified.contains(Axis.DEGRADED_MINOR));
            assertTrue(failingReport.axes.activated.contains(Axis.DEGRADED_MINOR));
            assertEquals(1, failingCheckDto.axes.specified.size());
            assertEquals(1, failingCheckDto.axes.activated.size());
            assertTrue(failingCheckDto.axes.specified.contains(Axis.DEGRADED_MINOR));
            assertTrue(failingCheckDto.axes.activated.contains(Axis.DEGRADED_MINOR));
        }
    }

    /**
     * Tests that we can provide a scanner to the health checks and have all detected methods registered automatically.
     */
    @Test
    public void testProvidingHealthCheckScanner() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(HealthCheckSpringTestConfiguration.class);

            // Provide a method scanner based on ClassGraph that implements HealthCheckMethodScanner
            context.registerBeanDefinition(ClassGraphHealthCheckMethodScanner.class.getSimpleName(),
                    BeanDefinitionBuilder.genericBeanDefinition(ClassGraphHealthCheckMethodScanner.class)
                            .addConstructorArgValue("com.storebrand.healthcheck.spring.testhealthchecks2")
                            .setScope(BeanDefinition.SCOPE_SINGLETON)
                            .getBeanDefinition()
            );

            context.refresh();
            HealthCheckRegistry healthCheckRegistry = context.getBean(HealthCheckRegistry.class);

            // Verify that the health check is registered and running
            assertEquals(1, healthCheckRegistry.getRegisteredHealthChecks().size());
            RegisteredHealthCheck registeredHealthCheck = healthCheckRegistry.getRegisteredHealthChecks().get(0);
            assertEquals("Scanner check", registeredHealthCheck.getMetadata().name);
            assertTrue(registeredHealthCheck.getMetadata().sync);
            assertFalse(registeredHealthCheck.getMetadata().async);
            assertTrue(registeredHealthCheck.isRunning());

            // Get a report and check that it is as expected
            HealthCheckReportDto report = healthCheckRegistry.createReport(new CreateReportRequest());
            assertEquals(1, report.healthChecks.size());
            HealthCheckDto checkDto = report.healthChecks.get(0);
            assertEquals("Scanner check", checkDto.name);

            HealthCheckAssertions.assertThat(report.axes)
                    .hasTheFollowingSpecifiedAxes(Axis.PROCESS_ERROR, Axis.AFFECTS_CUSTOMERS)
                    .hasTheFollowingActivatedAxes(Axis.PROCESS_ERROR, Axis.AFFECTS_CUSTOMERS);

            HealthCheckAssertions.assertThat(checkDto.axes)
                    .hasTheFollowingSpecifiedAxes(Axis.PROCESS_ERROR, Axis.AFFECTS_CUSTOMERS)
                    .hasTheFollowingActivatedAxes(Axis.PROCESS_ERROR, Axis.AFFECTS_CUSTOMERS);
        }
    }

    /**
     * Test that we can combine bean scanning and ClassGraph provided scanning, and that health checks are only
     * registered once.
     */
    @Test
    public void testCombiningBeanScanningAndClassGraphScanning() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(HealthCheckSpringTestConfiguration.class);

            // Provide a method scanner based on ClassGraph that implements HealthCheckMethodScanner
            context.registerBeanDefinition(ClassGraphHealthCheckMethodScanner.class.getSimpleName(),
                    BeanDefinitionBuilder.genericBeanDefinition(ClassGraphHealthCheckMethodScanner.class)
                            .addConstructorArgValue("com.storebrand.healthcheck.spring")
                            .setScope(BeanDefinition.SCOPE_SINGLETON)
                            .getBeanDefinition()
            );

            // Also provide bean scanning
            context.scan("com.storebrand.healthcheck.spring");

            context.refresh();
            HealthCheckRegistry healthCheckRegistry = context.getBean(HealthCheckRegistry.class);

            // Verify that both health checks are registered and running
            assertEquals(2, healthCheckRegistry.getRegisteredHealthChecks().size());
            assertTrue(healthCheckRegistry.getRegisteredHealthChecks().stream()
                    .allMatch(RegisteredHealthCheck::isRunning));
        }
    }

    @Configuration
    @EnableHealthChecks(projectName = "TestProject", projectVersion = "TestVersion1")
    public static class HealthCheckSpringTestConfiguration {
    }
}
