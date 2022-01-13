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

package com.storebrand.healthcheck.spring.testhealthchecks2;


import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.Responsible;
import com.storebrand.healthcheck.annotation.HealthCheck;

/**
 * Simple class with a {@link HealthCheck} annotated method that can be scanned for in order to test class path
 * scanning.
 */
public class ClassWithHealthCheck {
    @HealthCheck(name = "Scanner check", async = false)
    public void failingHealthCheck(CheckSpecification specs) {
        specs.check(Responsible.DEVELOPERS, Axis.of(Axis.PROCESS_ERROR, Axis.AFFECTS_CUSTOMERS),
                context -> context.fault("We have a process error affecting customers"));
    }
}
