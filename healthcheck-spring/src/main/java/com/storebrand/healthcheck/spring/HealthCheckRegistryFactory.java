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

import java.time.Clock;
import java.util.Optional;

import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.BasicMarkerFactory;
import org.springframework.beans.factory.config.AbstractFactoryBean;

import com.storebrand.healthcheck.HealthCheckLogger;
import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;
import com.storebrand.healthcheck.impl.HealthCheckRegistryImpl;
import com.storebrand.healthcheck.impl.ServiceInfo;
import com.storebrand.healthcheck.output.HealthCheckTextOutput;

/**
 * Bean factory for creating the {@link HealthCheckRegistryImpl} and also to shut down async threads on exit.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class HealthCheckRegistryFactory extends AbstractFactoryBean<HealthCheckRegistryImpl> {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckRegistryFactory.class);

    @Inject
    private Optional<Clock> _clock;
    @Inject
    private Optional<HealthCheckLogger> _healthCheckLogger;

    @Inject
    private ServiceInfo _serviceInfo;

    public HealthCheckRegistryFactory() {
        setSingleton(true);
    }

    @Override
    public Class<?> getObjectType() {
        return HealthCheckRegistryImpl.class;
    }

    @Override
    protected HealthCheckRegistryImpl createInstance() {
        Clock clock = _clock.orElse(Clock.systemDefaultZone());
        HealthCheckLogger logger = _healthCheckLogger.orElse(new DefaultSlf4jHealthCheckLogger());

        return new HealthCheckRegistryImpl(clock, logger, _serviceInfo);
    }

    @PreDestroy
    public void stopHealthChecks() {
        try {
            getObject().shutdown();
        }
        // CHECKSTYLE IGNORE IllegalCatch FOR NEXT 1 LINES - getObject() from Spring throws Exception.
        catch (Exception e) {
            // We are shutting down, and should be able to safely ignore this. We will still log a warning, just in
            // case it becomes an issue.
            log.warn("Error stopping async health checks and shutting down HealthCheckRegistry", e);
        }
    }

    public static class DefaultSlf4jHealthCheckLogger implements HealthCheckLogger {
        private static final Logger log = LoggerFactory.getLogger(DefaultSlf4jHealthCheckLogger.class);

        @Override
        public void logHealthCheckResult(HealthCheckDto dto) {
            // Setting max char length, so we don't spam logs with insane log lines. Health checks should not return
            // gigantic messages.
            int charLength = 12_000;
            String body = HealthCheckTextOutput.getBodyFromHealthCheck(dto);
            if (body.length() > charLength) {
                body = body.substring(0, charLength) + " (...) CHOPPED by Health check logger. Above " + charLength
                        + " chars limit.";
            }
            BasicMarkerFactory basicMarkerFactory = new BasicMarkerFactory();
            Marker healthCheckNameMarker = basicMarkerFactory.getMarker("HealthCheck.name");
            log.warn(healthCheckNameMarker, "HealthCheck [" + dto.name + "] returned activated axes ["
                    + dto.axes.activated.toString() + "] of specified axes ["
                    + dto.axes.specified.toString() + "]:\n" + body);
        }
    }
}
