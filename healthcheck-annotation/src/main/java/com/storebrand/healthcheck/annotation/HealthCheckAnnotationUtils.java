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

package com.storebrand.healthcheck.annotation;

import java.lang.reflect.Method;
import java.util.Collection;

import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckMetadata.HealthCheckMetadataBuilder;
import com.storebrand.healthcheck.HealthCheckRegistry;

/**
 * Utility class for interacting with {@link HealthCheck} annotated methods.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public final class HealthCheckAnnotationUtils {

    private HealthCheckAnnotationUtils() {
        // Utility class - hiding constructor
    }

    /**
     * Validates if a method is of format "void methodName(CheckSpecification)".
     *
     * @param method
     *         the health check method we should validate.
     * @return true if this is a valid health check method.
     */
    public static boolean isValidHealthCheckMethod(Method method) {
        // ?: Is the method return type void?
        if (method.getReturnType() != Void.TYPE) {
            // -> Nope, then this is not a valid method for HealthChecks
            return false;
        }

        // Check if there is exactly one argument of type CheckSpecification
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 1
                && parameterTypes[0] == CheckSpecification.class;
    }

    /**
     * Extract metadata from the {@link HealthCheck} annotation on a {@link Method}.
     *
     * @param method
     *         the method we should extract metadata from.
     * @return {@link HealthCheckMetadata} based on the {@link HealthCheck} annotation on the method.
     */
    public static HealthCheckMetadata getMetadata(Method method) {
        if (!method.isAnnotationPresent(HealthCheck.class)) {
            throw new IllegalStateException("Annotation @HealthCheck not present on method.");
        }
        if (!isValidHealthCheckMethod(method)) {
            throw new IllegalArgumentException(
                    "Method must return void, and have exactly one argument of type CheckSpecification.");
        }
        HealthCheck annotation = method.getAnnotation(HealthCheck.class);
        String name = annotation.name();
        if ("".equals(name)) {
            name = annotation.value();
        }
        if ("".equals(name)) {
            throw new IllegalStateException("Annotation @HealthCheck is missing both name and value"
                    + " - one must be present.");
        }

        // :: Determine if we should run synchronous or not.
        // As we started with async as an argument, but are changing to sync, we need to support both until we can
        // remove async.
        boolean sync = false;
        // ?: Have we explicitly said we want this sync?
        if (annotation.sync()) {
            // -> Yes, set async to false.
            sync = true;
        }
        // ?: Then have we set async to false? This is the legacy way to make it synchronous, that we need to support.
        else if (!annotation.async()) {
            // -> Yes, then we set sync to true here as well.
            sync = true;
        }
        return new HealthCheckMetadataBuilder(name)
                .description(annotation.description())
                .type(annotation.type())
                .onBehalfOf(annotation.onBehalfOf())
                .sync(sync)
                .intervalInSeconds(annotation.intervalInSeconds())
                .intervalWhenNotOkInSeconds(annotation.intervalWhenNotOkInSeconds())
                .expectedMaximumRunTimeInSeconds(annotation.expectedMaximumRunTimeInSeconds())
                .build();
    }

    /**
     * Convenience method for registering a {@link HealthCheck} annotated method in a {@link HealthCheckRegistry}. It
     * will use the given {@link HealthCheckInstanceResolver} to create an instance that is used to call the given
     * method.
     *
     * @param method
     *         the method that contains the specification for the health check we want to register. Must be annotated
     *         with {@link HealthCheck}.
     * @param healthCheckRegistry
     *         the registry we should register the health check in.
     * @param instanceResolver
     *         the instance resolver used to fetch an instance we can call the method on.
     */
    public static void registerAnnotatedMethod(Method method, HealthCheckRegistry healthCheckRegistry,
            HealthCheckInstanceResolver instanceResolver) {
        HealthCheckMetadata metadata = getMetadata(method);
        // Determine if a name was provided during registration, or if class name should be used.
        String baseName = metadata.name != null ? metadata.name
                : method.getDeclaringClass().getSimpleName() + "." + method.getName();
        Collection<?> instances = instanceResolver.getInstancesFor(method.getDeclaringClass());
        int i = 0;
        for (Object instance : instances) {
            String name = instances.size() == 1 ? baseName : baseName + "#" + ++i;
            healthCheckRegistry.registerHealthCheck(metadata.withName(name), method, instance);
        }
    }
}
