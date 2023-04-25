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
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.Test;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.HealthCheckRegistry.CreateReportRequest;
import com.storebrand.healthcheck.HealthCheckReportDto;
import com.storebrand.healthcheck.Responsible;

/**
 * Tests for the {@link HealthCheckRegistryImpl}.
 */
public class HealthCheckRegistryImplTests {

    private volatile boolean _shouldFailProcessCheck = false;
    private volatile boolean _shouldFailReadinessCheck = true;

    @Test
    public void testRegisteringAndRunningHealthChecks() throws InterruptedException {
        // :: Setup
        Instant startTime = Instant.parse("2020-01-01T01:01:01Z");
        AdjustableClock clock = new AdjustableClock(startTime);
        TestLogRecorder logRecorder = new TestLogRecorder();
        ServiceInfo serviceInfo = new ServiceInfo("TESTNAME", "TESTVERSION");
        HealthCheckRegistry registry = new HealthCheckRegistryImpl(clock, logRecorder, serviceInfo);
        try {
            // :: Register health checks
            registry.registerHealthCheck(HealthCheckMetadata.builder()
                            .name("Process check")
                            .async(true)
                            .build(),
                    specs -> specs.check(Responsible.BACK_OFFICE,
                            Axis.of(Axis.PROCESS_ERROR, Axis.MANUAL_INTERVENTION_REQUIRED),
                            context -> context.faultConditionally(_shouldFailProcessCheck,
                                    "This checks important process.")));
            registry.registerHealthCheck(HealthCheckMetadata.builder()
                            .name("Readiness check")
                            .async(false)
                            .build(),
                    specs -> specs.check(Responsible.DEVELOPERS,
                            Axis.NOT_READY,
                            context -> _shouldFailReadinessCheck
                                    ? context.fault("We are not ready yet.")
                                    : context.ok("We are ready.")));

            // :: Start health checks
            registry.startHealthChecks();
            Thread.sleep(50);

            // :: Update adjustable clock
            Instant firstTime = Instant.parse("2020-01-01T01:01:03Z");
            clock.setCurrentTime(firstTime);

            // :: Generate a report
            HealthCheckReportDto report1 = registry.createReport(new CreateReportRequest());

            // :: Assert result from report 1
            assertFalse(report1.ready);
            assertTrue(report1.live);
            assertFalse(report1.criticalFault);
            assertEquals(1, report1.axes.activated.size());
            assertEquals(3, report1.axes.specified.size());
            assertTrue(report1.axes.activated.contains(Axis.NOT_READY));
            assertTrue(report1.axes.specified.contains(Axis.NOT_READY));
            assertTrue(report1.axes.specified.contains(Axis.PROCESS_ERROR));
            assertTrue(report1.axes.specified.contains(Axis.MANUAL_INTERVENTION_REQUIRED));

            // :: Change conditions
            _shouldFailProcessCheck = true;
            _shouldFailReadinessCheck = false;

            // :: Update adjustable clock before second report
            // Note, this should make the report stale, as we have used more than the expected amount of time.
            Instant secondTime = Instant.parse("2020-01-01T03:00:00Z");
            clock.setCurrentTime(secondTime);

            // :: Generate a new report
            HealthCheckReportDto report2 = registry.createReport(new CreateReportRequest());

            // :: Assert result from report 2
            assertTrue(report2.ready);
            assertTrue(report2.live);
            assertFalse(report2.criticalFault);
            assertEquals(1, report2.axes.activated.size());
            assertEquals(3, report2.axes.specified.size());
            assertTrue(report2.axes.activated.contains(Axis.SYS_STALE));
            assertTrue(report2.axes.specified.contains(Axis.NOT_READY));
            assertTrue(report2.axes.specified.contains(Axis.PROCESS_ERROR));
            assertTrue(report2.axes.specified.contains(Axis.MANUAL_INTERVENTION_REQUIRED));

            // :: Update adjustable clock before triggering update
            Instant thirdTime = Instant.parse("2020-01-01T04:00:00Z");
            clock.setCurrentTime(thirdTime);

            // :: Trigger update of process test
            registry.triggerUpdateForHealthCheck("Process check");
            // Wait a bit for check to run in background thread
            Thread.sleep(50);

            // :: Generate a final report
            HealthCheckReportDto report3 = registry.createReport(new CreateReportRequest());
            // These axes should have been updated now
            assertEquals(2, report3.axes.activated.size());
            assertEquals(3, report3.axes.specified.size());
            assertTrue(report3.axes.activated.contains(Axis.PROCESS_ERROR));
            assertTrue(report3.axes.activated.contains(Axis.MANUAL_INTERVENTION_REQUIRED));
            assertTrue(report3.axes.specified.contains(Axis.NOT_READY));
            assertTrue(report3.axes.specified.contains(Axis.PROCESS_ERROR));
            assertTrue(report3.axes.specified.contains(Axis.MANUAL_INTERVENTION_REQUIRED));

            // :: Check logging
            // The readiness check should have failed, and we should have one log entry.
            assertEquals(1, logRecorder.getLoggedAt(firstTime).size());
            // As the readiness probe is ok, and we are getting the result from the other from the cache we did not run
            // any checks that failed at this moment. We should therefore expect 0 logged errors the second time.
            assertEquals(0, logRecorder.getLoggedAt(secondTime).size());
            // The third time we have forced the process check to run, so it should have logged an error.
            assertEquals(1, logRecorder.getLoggedAt(thirdTime).size());
        }
        finally {
            // :: Ensure shutdown
            registry.shutdown();
        }
    }


    /**
     * Clock that can be adjusted during testing.
     */
    public static final class AdjustableClock extends Clock {
        private Instant _currentTime;

        public AdjustableClock(Instant currentTime) {
            _currentTime = currentTime;
        }

        public void setCurrentTime(Instant currentTime) {
            _currentTime = currentTime;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(_currentTime, zone);
        }

        @Override
        public Instant instant() {
            return _currentTime;
        }
    }
}
