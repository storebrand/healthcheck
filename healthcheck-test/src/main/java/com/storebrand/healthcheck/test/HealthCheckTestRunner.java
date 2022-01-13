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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;

import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckInstance;
import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;
import com.storebrand.healthcheck.HealthCheckRegistryImpl;
import com.storebrand.healthcheck.HealthCheckRegistryImpl.HealthCheckResult;

/**
 * Special class for executing a health check specification method immediately and return a {@link HealthCheckDto}
 * result without actually registering a permanent health check. This can be useful for unit tests and for places where
 * we just want the result of a health check method directly without dealing with async background threads.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public final class HealthCheckTestRunner {

    private HealthCheckTestRunner() {
        // Utility class
    }

    private static final Constructor<HealthCheckInstance> __healthCheckInstanceConstructor;
    private static final Method __performHealthCheckMethod;
    private static final Method __healthCheckResultToDtoMethod;

    /*
     * We use reflection to get access to package and private constructor and methods in the impl package, and we save
     * them in static fields so they can be reused by many tests.
     */
    static {
        try {
            __healthCheckInstanceConstructor =
                    HealthCheckInstance.class.getDeclaredConstructor(HealthCheckMetadata.class, Clock.class);
            __healthCheckInstanceConstructor.setAccessible(true);
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError("This constructor should exist", e);
        }

        try {
            __performHealthCheckMethod = HealthCheckInstance.class.getDeclaredMethod("performHealthCheck");
            __performHealthCheckMethod.setAccessible(true);
            __healthCheckResultToDtoMethod = HealthCheckRegistryImpl.class.getDeclaredMethod("healthCheckResultToDto",
                    HealthCheckResult.class, Instant.class);
            __healthCheckResultToDtoMethod.setAccessible(true);
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError("Method should exist.", e);
        }
    }

    /**
     * Point this to a method that specifies the health check you want to run, and this method will execute the health
     * check immediately and return the result.
     *
     * @param clock
     *         supply a clock
     * @param method
     *         a consumer that will specify the health check that we want to execute.
     * @return the result of the specified health check.
     */
    public static HealthCheckDto runHealthCheck(Clock clock, Consumer<CheckSpecification> method) {
        HealthCheckMetadata metadata = HealthCheckMetadata.create("Temporary health check");

        // :: Create an instance of HealthCheckInstance via reflection, as the constructor is only accessible in impl.
        HealthCheckInstance instance;
        try {
            instance = __healthCheckInstanceConstructor.newInstance(metadata, clock);
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Creating this method should always work", e);
        }

        // :: Run the supplied method for specifying the HealthCheck and commit.
        method.accept(instance);
        instance.commit();

        // :: Perform the health check via reflection, as the performHealthCheck method is only accessible in impl.
        try {
            return (HealthCheckDto) __healthCheckResultToDtoMethod
                    .invoke(null, __performHealthCheckMethod.invoke(instance), clock.instant());
        }
        catch (IllegalAccessException e) {
            throw new AssertionError("Invoking this method should always work", e);
        }
        catch (InvocationTargetException e) {
            throw new RuntimeException("Exception when executing the health check", e);
        }
    }

    /**
     * Convenience method that employs {@link Clock#systemDefaultZone()} as clock, and forwards to {@link
     * #runHealthCheck(Clock, Consumer)}
     *
     * @param method
     *         a consumer that will specify the health check that we want to execute.
     * @return the result of the specified health check.
     */
    public static HealthCheckDto runHealthCheck(Consumer<CheckSpecification> method) {
        return runHealthCheck(Clock.systemDefaultZone(), method);
    }
}
