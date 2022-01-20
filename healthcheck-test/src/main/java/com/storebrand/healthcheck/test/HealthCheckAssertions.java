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

import static java.util.stream.Collectors.toSet;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.HealthCheckReportDto.AxesDto;

/**
 * Test helper for asserting that a health check returns the expected specified and activated {@link Axis} in a
 * {@link AxesDto}.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public final class HealthCheckAssertions {

    private HealthCheckAssertions() {
        // Utility test helper
    }

    public static AssertAxes assertThat(AxesDto dto) {
        return new AssertAxes(dto);
    }

    public static final class AssertAxes {
        private final AxesDto _axesDto;

        private AssertAxes(AxesDto axesDto) {
            _axesDto = axesDto;
        }

        public AssertAxes hasTheFollowingSpecifiedAxes(Axis... axes) {
            assertAxesInSet(axes, _axesDto.specified, "Specified axes does not match expected specified axes.");
            return this;
        }

        public AssertAxes hasTheFollowingActivatedAxes(Axis... axes) {
            assertAxesInSet(axes, _axesDto.activated, "Activated axes does not match expected activated axes.");
            return this;
        }
    }

    private static void assertAxesInSet(Axis[] expected, Set<Axis> axes, String message) {
        Set<Axis> expectedSet = new TreeSet<>(Arrays.asList(expected));
        if (expectedSet.equals(axes)) {
            return;
        }
        throw new AssertionError(message + " Expected axes ["
                + String.join(", ", expectedSet.stream().map(Axis::toString).collect(toSet())) + "]"
                + ", but got ["
                + String.join(", ", axes.stream().map(Axis::toString).collect(toSet())) + "]");
    }
}
