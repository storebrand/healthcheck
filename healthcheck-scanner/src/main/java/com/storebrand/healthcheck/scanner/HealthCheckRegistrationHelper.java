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

package com.storebrand.healthcheck.scanner;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.storebrand.healthcheck.annotation.HealthCheck;
import com.storebrand.healthcheck.annotation.HealthCheckAnnotationUtils;
import com.storebrand.healthcheck.annotation.HealthCheckInstanceResolver;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckRegistry;

/**
 * Utility class that will scan for all {@link HealthCheck} annotated methods using the ClassGraph library, and register
 * them in a {@link HealthCheckRegistry}, using a {@link HealthCheckInstanceResolver} to get instances for the health
 * checks.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public final class HealthCheckRegistrationHelper {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckRegistrationHelper.class);

    private static final String LOGGER_PREFIX = "#HEALTH_CHECK_REGISTRATION_HELPER# ";

    private HealthCheckRegistrationHelper() {
        // Utility class
    }

    /**
     * Scans for and registers health checks. This will also check if a method has been registered before, to avoid
     * double registration of the same method.
     *
     * @param registry
     *         the {@link HealthCheckRegistry} to register new health checks in.
     * @param instanceResolver
     *         resolver used to get instances for new health checks.
     * @param packageNames
     *         packages to scan for {@link HealthCheck} annotated methods.
     * @param classLoaders
     *         optional collection of classloaders to override default classloader.
     */
    public static void scanAndRegisterHealthChecks(HealthCheckRegistry registry,
            HealthCheckInstanceResolver instanceResolver, Collection<String> packageNames,
            Collection<ClassLoader> classLoaders) {
        log.info(LOGGER_PREFIX
                + "Scanning and registering all @HealthCheck annotated methods that are not currently registered from ["
                + String.join(", ", packageNames) + "]");

        List<Method> methods = ClassGraphHealthCheckMethodScanner.findHealthCheckMethods(packageNames, classLoaders);

        for (Method method : methods) {
            if (registry.isMethodRegistered(method)) {
                log.info(LOGGER_PREFIX
                        + "Skipping registering method [" + method + "] as it has already been registered.");
                continue;
            }

            HealthCheckMetadata metadata = HealthCheckAnnotationUtils.getMetadata(method);

            Collection<?> instances = instanceResolver.getInstancesFor(method.getDeclaringClass());

            // In case resolving the instance also registers the health check direcly we do a sanity check here
            // ?: Have the method been registered as a consequence of resolving the instance?
            if (registry.isMethodRegistered(method)) {
                log.info(LOGGER_PREFIX
                        + "Skipping registering method [" + method + "] as it was registered automatically when"
                        + " resolving the instance for the method.");
                continue;
            }

            // Determine if a name was provided during registration, or if class name should be used.
            String baseName = metadata.name != null ? metadata.name
                    : method.getDeclaringClass().getSimpleName() + "." + method.getName();

            int i = 0;
            for (Object instance : instances) {
                String name = instances.size() == 1 ? baseName : baseName + "#" + ++i;
                registry.registerHealthCheck(metadata.withName(name), method, instance);
            }
        }
    }
}
