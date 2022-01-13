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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleHealthCheckSettings implements HealthCheckSettings {
    private static final Logger log = LoggerFactory.getLogger(SimpleHealthCheckSettings.class);

    private final String _projectName;
    private final String _projectVersion;

    SimpleHealthCheckSettings() {
        this("UNDEFINED", "UNDEFINED");
        log.warn("HealthCheck project name and version UNDEFINED"
                + " - please supply an implementation of HealthCheckSettings in the Spring Context.");
    }

    public SimpleHealthCheckSettings(String projectName, String projectVersion) {
        _projectName = projectName;
        _projectVersion = projectVersion;
    }

    @Override
    public String getProjectName() {
        return _projectName;
    }

    @Override
    public String getProjectVersion() {
        return _projectVersion;
    }
}
