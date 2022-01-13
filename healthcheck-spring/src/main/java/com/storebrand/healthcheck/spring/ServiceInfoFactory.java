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

import java.util.Optional;

import javax.inject.Inject;

import org.springframework.beans.factory.config.AbstractFactoryBean;

import com.storebrand.healthcheck.HealthCheckRegistry.InfoPropertiesSupplier;
import com.storebrand.healthcheck.ServiceInfo;

public class ServiceInfoFactory extends AbstractFactoryBean<ServiceInfo> {

    @Inject
    private Optional<HealthCheckSettings> _healthCheckSettings;

    @Inject
    private Optional<InfoPropertiesSupplier> _infoPropertiesSupplier;

    public ServiceInfoFactory() {
        setSingleton(true);
    }

    @Override
    public Class<?> getObjectType() {
        return ServiceInfo.class;
    }

    @Override
    protected ServiceInfo createInstance() {
        HealthCheckSettings settings = _healthCheckSettings.orElseGet(SimpleHealthCheckSettings::new);
        return new ServiceInfo(settings.getProjectName(), settings.getProjectVersion(),
                _infoPropertiesSupplier.orElse(null));
    }
}
