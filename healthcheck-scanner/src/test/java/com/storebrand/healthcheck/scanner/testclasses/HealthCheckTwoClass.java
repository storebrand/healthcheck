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

package com.storebrand.healthcheck.scanner.testclasses;

import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.annotation.HealthCheck;

public class HealthCheckTwoClass {
    @HealthCheck(name = "Method 3")
    public void method3(CheckSpecification specs) {

    }

    public void methodWithoutHealthCheck() {

    }
}
