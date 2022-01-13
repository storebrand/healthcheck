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

package com.storebrand.healthcheck.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;
import com.storebrand.healthcheck.Responsible;

/**
 * Test that the {@link HealthCheckTestRunner} can run a health check.
 */
public class HealthCheckTestRunnerTest {

    @Test
    public void testHealthCheckTestRunner() {
        // :: Act
        HealthCheckDto resultOk = HealthCheckTestRunner.runHealthCheck(this::healthCheckMethodOk);
        HealthCheckDto resultWithFault = HealthCheckTestRunner.runHealthCheck(this::healthCheckMethodWithFault);

        // :: Perform some basic assertions
        assertEquals(1, resultOk.axes.specified.size());
        assertEquals(0, resultOk.axes.activated.size());
        assertTrue(resultOk.axes.specified.contains(Axis.CRITICAL_WAKE_PEOPLE_UP));

        HealthCheckAssertions.assertThat(resultOk.axes)
                .hasTheFollowingActivatedAxes()
                .hasTheFollowingSpecifiedAxes(Axis.CRITICAL_WAKE_PEOPLE_UP);

        assertEquals(1, resultWithFault.axes.specified.size());
        assertEquals(1, resultWithFault.axes.activated.size());
        assertTrue(resultWithFault.axes.specified.contains(Axis.NOT_READY));
        assertTrue(resultWithFault.axes.activated.contains(Axis.NOT_READY));

        HealthCheckAssertions.assertThat(resultWithFault.axes)
                .hasTheFollowingActivatedAxes(Axis.NOT_READY)
                .hasTheFollowingSpecifiedAxes(Axis.NOT_READY);
    }

    @Test
    public void testInconsistencyAxisWithRunner() {
        // :: Act
        HealthCheckDto withLegacyAxis = HealthCheckTestRunner
                .runHealthCheck(this::healthCheckWithLegacyInternalInconsistency);
        HealthCheckDto withNewInconsistency = HealthCheckTestRunner
                .runHealthCheck(this::healthCheckWithInconsistency);

        // :: Perform some basic assertions
        assertEquals(2, withLegacyAxis.axes.specified.size());
        assertEquals(0, withLegacyAxis.axes.activated.size());
        assertTrue(withLegacyAxis.axes.specified.contains(Axis.INTERNAL_INCONSISTENCY));
        assertTrue(withLegacyAxis.axes.specified.contains(Axis.INCONSISTENCY));

        // Make sure that the assertion tool also handles automatically adding the new INCONSISTENCY.
        HealthCheckAssertions.assertThat(withLegacyAxis.axes)
                        .hasTheFollowingSpecifiedAxes(Axis.INTERNAL_INCONSISTENCY);

        assertEquals(2, withNewInconsistency.axes.specified.size());
        assertEquals(0, withNewInconsistency.axes.activated.size());
        assertTrue(withNewInconsistency.axes.specified.contains(Axis.INTERNAL_INCONSISTENCY));
        assertTrue(withNewInconsistency.axes.specified.contains(Axis.INCONSISTENCY));

        // Make sure that the assertion tool also handles automatically adding the legacy INTERNAL_INCONSISTENCY.
        HealthCheckAssertions.assertThat(withNewInconsistency.axes)
                .hasTheFollowingSpecifiedAxes(Axis.INCONSISTENCY);
    }

    /**
     * Very simple health check method that verifies that we are able to run a simple test that returns OK.
     */
    public void healthCheckMethodOk(CheckSpecification spec) {
        spec.check(Responsible.DEVELOPERS, Axis.CRITICAL_WAKE_PEOPLE_UP, context -> context.ok("This is ok"));
    }

    /**
     * Very simple health check method that verifies that we are able to run a simple test that fails.
     */
    public void healthCheckMethodWithFault(CheckSpecification spec) {
        spec.check(Responsible.DEVELOPERS, Axis.NOT_READY, context -> context.fault("This is not ok"));
    }

    /**
     * Simple check for verifying that adding {@link Axis#INTERNAL_INCONSISTENCY} will also cause
     * {@link Axis#INCONSISTENCY} to be set.
     */
    public void healthCheckWithLegacyInternalInconsistency(CheckSpecification spec) {
        spec.check(Responsible.DEVELOPERS, Axis.INTERNAL_INCONSISTENCY,
                context -> context.ok("This should have two axes, due to legacy INTERNAL_INCONSISTENCY"));
    }

    /**
     * Simple check for verifying that adding {@link Axis#INCONSISTENCY} will also cause
     * {@link Axis#INTERNAL_INCONSISTENCY} to be set.
     */
    public void healthCheckWithInconsistency(CheckSpecification spec) {
        spec.check(Responsible.DEVELOPERS, Axis.INCONSISTENCY,
                context -> context.ok("This should have two axes, due to legacy INTERNAL_INCONSISTENCY"));
    }
}
