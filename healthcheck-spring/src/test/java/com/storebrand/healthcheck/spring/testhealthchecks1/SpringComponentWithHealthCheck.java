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

package com.storebrand.healthcheck.spring.testhealthchecks1;

import org.springframework.stereotype.Component;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.Responsible;
import com.storebrand.healthcheck.annotation.HealthCheck;

/**
 * Spring component with a simple health check that can be toggled. Used for testing health checks with Spring.
 */
@Component
public class SpringComponentWithHealthCheck {

    private boolean _failCheck = false;

    public void setFailCheck(boolean failCheck) {
        _failCheck = failCheck;
    }

    @HealthCheck(name = "Spring component health check", sync = true)
    public void failingHealthCheck(CheckSpecification specs) {
        specs.check(Responsible.DEVELOPERS, Axis.of(Axis.DEGRADED_MINOR),
                context -> _failCheck
                        ? context.fault("We are degraded.")
                        : context.ok("We are OK!"));
    }

}
