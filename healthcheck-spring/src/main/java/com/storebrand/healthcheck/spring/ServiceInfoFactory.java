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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AbstractFactoryBean;

import com.storebrand.healthcheck.HealthCheckRegistry.InfoPropertiesSupplier;
import com.storebrand.healthcheck.impl.ServiceInfo;

public class ServiceInfoFactory extends AbstractFactoryBean<ServiceInfo> {

    @Autowired(required = false)
    private HealthCheckSettings _healthCheckSettings;
    @Autowired(required = false)
    private InfoPropertiesSupplier _infoPropertiesSupplier;

    @SuppressWarnings("this-escape") // setSingleton is the inherited Spring API for declaring scope; safe in our usage.
    public ServiceInfoFactory() {
        setSingleton(true);
    }

    @Override
    public Class<?> getObjectType() {
        return ServiceInfo.class;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (_healthCheckSettings == null) {
            _healthCheckSettings = new SimpleHealthCheckSettings();
        }

        super.afterPropertiesSet();
    }

    @Override
    protected ServiceInfo createInstance() {
        return new ServiceInfo(_healthCheckSettings.getProjectName(), _healthCheckSettings.getProjectVersion(),
                _infoPropertiesSupplier);
    }
}
