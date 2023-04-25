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

package com.storebrand.healthcheck.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.Test;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.Responsible;
import com.storebrand.healthcheck.impl.HealthCheckRegistryImpl.HealthCheckResult;
import com.storebrand.healthcheck.impl.Status.StatusInfoOnly;
import com.storebrand.healthcheck.impl.Status.StatusLink;
import com.storebrand.healthcheck.impl.Status.StatusWithAxes;
import com.storebrand.healthcheck.impl.Status.StatusWithThrowable;

/**
 * Tests for {@link HealthCheckInstance}.
 */
public class HealthCheckInstanceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.now(), ZoneId.systemDefault());

    private boolean _healthCheckShouldFail;
    private String _dynamicText;

    @Test
    public void testOkHealthCheck() {
        // :: Arrange
        HealthCheckMetadata metadata = HealthCheckMetadata.create("TestOk");
        HealthCheckInstance healthCheck1Instance = new HealthCheckInstance(metadata, FIXED_CLOCK);

        // Specify check
        simpleHealthCheck(healthCheck1Instance);
        healthCheck1Instance.commit();
        _healthCheckShouldFail = false;
        _dynamicText = "Something dynamic";

        // :: Act
        HealthCheckResult result = healthCheck1Instance.performHealthCheck();

        // :: Assert
        // Check overall result
        assertSame(metadata, result.getMetadata());
        assertEquals(metadata.name, result.getName());
        assertEquals("Structured data", result.getStructuredData());
        assertEquals(2, result.getAggregatedAxes().size());
        assertFalse(result.getAggregatedAxes().get(Axis.CRITICAL_WAKE_PEOPLE_UP));
        assertFalse(result.getAggregatedAxes().get(Axis.AFFECTS_CUSTOMERS));

        // Check each status
        List<Status> statuses = result.getStatuses();
        assertEquals(5, statuses.size());

        StatusInfoOnly status1 = (StatusInfoOnly) statuses.get(0);
        StatusInfoOnly status2 = (StatusInfoOnly) statuses.get(1);
        StatusInfoOnly status3 = (StatusInfoOnly) statuses.get(2);
        StatusWithAxes status4 = (StatusWithAxes) statuses.get(3);
        StatusLink status5 = (StatusLink) statuses.get(4);

        assertEquals("First line", status1.getDescription());
        assertEquals(_dynamicText, status2.getDescription());
        assertEquals("Check line", status3.getDescription());
        assertEquals("We are ok", status4.getDescription());
        assertEquals(2, status4.getAxes().size());
        assertFalse(status4.getAxes().get(Axis.CRITICAL_WAKE_PEOPLE_UP));
        assertFalse(status4.getAxes().get(Axis.AFFECTS_CUSTOMERS));
        assertEquals("Link text", status5.getLinkDisplayText());
        assertEquals("http://example.com", status5.getUrl());
    }

    @Test
    public void testFaultyHealthCheck() {
        // :: Arrange
        HealthCheckMetadata metadata = HealthCheckMetadata.create("TestFaulty");
        HealthCheckInstance healthCheck1Instance = new HealthCheckInstance(metadata, FIXED_CLOCK);

        // Specify check
        simpleHealthCheck(healthCheck1Instance);
        healthCheck1Instance.commit();
        _healthCheckShouldFail = true;
        _dynamicText = "Something else dynamic";

        // :: Act
        HealthCheckResult result = healthCheck1Instance.performHealthCheck();

        // :: Assert
        // Check overall result
        assertSame(metadata, result.getMetadata());
        assertEquals(metadata.name, result.getName());
        assertEquals("Structured data", result.getStructuredData());
        assertEquals(2, result.getAggregatedAxes().size());
        assertTrue(result.getAggregatedAxes().get(Axis.CRITICAL_WAKE_PEOPLE_UP));
        assertTrue(result.getAggregatedAxes().get(Axis.AFFECTS_CUSTOMERS));

        // Check each status
        List<Status> statuses = result.getStatuses();
        assertEquals(5, statuses.size());

        StatusInfoOnly status1 = (StatusInfoOnly) statuses.get(0);
        StatusInfoOnly status2 = (StatusInfoOnly) statuses.get(1);
        StatusInfoOnly status3 = (StatusInfoOnly) statuses.get(2);
        StatusWithAxes status4 = (StatusWithAxes) statuses.get(3);
        StatusLink status5 = (StatusLink) statuses.get(4);

        assertEquals("First line", status1.getDescription());
        assertEquals(_dynamicText, status2.getDescription());
        assertEquals("Check line", status3.getDescription());
        assertEquals("Health check not OK", status4.getDescription());
        assertEquals(2, status4.getAxes().size());
        assertTrue(status4.getAxes().get(Axis.CRITICAL_WAKE_PEOPLE_UP));
        assertTrue(status4.getAxes().get(Axis.AFFECTS_CUSTOMERS));
        assertEquals("Link text", status5.getLinkDisplayText());
        assertEquals("http://example.com", status5.getUrl());
    }

    @Test
    public void testCrashingHealthCheckShouldReportSysCrashedAndTriggerAllAxes() {
        // :: Arrange
        HealthCheckMetadata metadata = HealthCheckMetadata.create("TestFaulty");
        HealthCheckInstance healthCheckInstance = new HealthCheckInstance(metadata, FIXED_CLOCK);

        // Specify check
        crashingHealthCheck(healthCheckInstance);
        healthCheckInstance.commit();

        // :: Act
        HealthCheckResult result = healthCheckInstance.performHealthCheck();

        // :: Assert
        List<Status> statuses = result.getStatuses();
        assertEquals(2, statuses.size());

        StatusWithThrowable status1 = (StatusWithThrowable) statuses.get(0);
        assertTrue(status1.isUnhandled());
        assertTrue(status1.getAxes().get(Axis.SYS_CRASHED));

        StatusWithAxes status2 = (StatusWithAxes) statuses.get(1);
        assertTrue(status2.getAxes().get(Axis.MANUAL_INTERVENTION_REQUIRED));
        assertTrue(status2.getAxes().get(Axis.DEGRADED_MINOR));
        assertEquals(2, status2.getAxes().size());

        assertTrue(result.getAggregatedAxes().get(Axis.SYS_CRASHED));
        assertTrue(result.getAggregatedAxes().get(Axis.MANUAL_INTERVENTION_REQUIRED));
        assertTrue(result.getAggregatedAxes().get(Axis.DEGRADED_MINOR));
        assertEquals(3, result.getAggregatedAxes().size());
    }

    public void simpleHealthCheck(CheckSpecification spec) {
        spec.staticText("First line");
        spec.dynamicText(context -> _dynamicText);
        spec.check(Responsible.DEVELOPERS, Axis.of(Axis.CRITICAL_WAKE_PEOPLE_UP, Axis.AFFECTS_CUSTOMERS), context -> {
            context.text("Check line");

            return _healthCheckShouldFail
                    ? context.fault("Health check not OK")
                    : context.ok("We are ok");
        });
        spec.link("Link text", "http://example.com");
        spec.structuredData(context -> "Structured data");
    }

    public void crashingHealthCheck(CheckSpecification spec) {
        spec.check(Responsible.DEVELOPERS, Axis.of(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.DEGRADED_MINOR), context -> {
            throw new RuntimeException("We should crash");
        });
    }
}
