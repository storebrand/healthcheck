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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.storebrand.healthcheck.annotation.HealthCheck;
import com.storebrand.healthcheck.annotation.HealthCheckAnnotationUtils;
import com.storebrand.healthcheck.annotation.HealthCheckMethodScanner;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

/**
 * Health check scanner for detecting methods annotated with {@link HealthCheck} using the ClassGraph library.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class ClassGraphHealthCheckMethodScanner implements HealthCheckMethodScanner {
    private static final Logger log = LoggerFactory.getLogger(ClassGraphHealthCheckMethodScanner.class);
    private final Collection<String> _packageNames;
    private final Collection<ClassLoader> _classLoaders;

    /**
     * Constructor that specifies the packages that should be scanned for {@link HealthCheck} annotated methods. Will
     * also scan sub packages.
     */
    public ClassGraphHealthCheckMethodScanner(Collection<String> packageNames, Collection<ClassLoader> classLoaders) {
        _packageNames = packageNames;
        _classLoaders = classLoaders;
    }

    /**
     * Constructor that specifies the packages that should be scanned for {@link HealthCheck} annotated methods. Will
     * also scan sub packages. This will use the default class loader of ClassGraph.
     */
    public ClassGraphHealthCheckMethodScanner(String... packageNames) {
        _packageNames = Arrays.asList(packageNames);
        _classLoaders = null;
    }

    /**
     * @return a set of {@link Method} that are annotated with {@link HealthCheck}.
     */
    @Override
    public Set<Method> getHealthCheckAnnotatedMethods() {
        return new HashSet<>(findHealthCheckMethods(_packageNames, _classLoaders));
    }

    /**
     * Static method that scans for methods annotated with {@link HealthCheck}, and returns a set of {@link Method}.
     *
     * @param packageNames
     *         the packages to scan. Will also scan sub packages.
     * @param classLoaders
     *         the class loaders we should supply to ClassGraph.
     * @return a set of {@link Method} that are annotated with {@link HealthCheck} found in the given packages.
     */
    public static List<Method> findHealthCheckMethods(Collection<String> packageNames, Collection<ClassLoader> classLoaders) {
        log.info("Using ClassGraph to scan for methods annotated with @HealthCheck");
        ClassGraph classGraph = new ClassGraph().enableAllInfo()
                .acceptPackages(packageNames.toArray(new String[0]));

        if (classLoaders != null) {
            log.info(" - Overriding default class loader");
            classGraph.overrideClassLoaders(classLoaders.toArray(new ClassLoader[0]));
        }

        try (ScanResult result = classGraph.scan()) {

            List<Method> healthCheckMethods = new ArrayList<>();

            ClassInfoList classInfos = result.getClassesWithMethodAnnotation(HealthCheck.class);

            for (ClassInfo classInfo : classInfos) {
                log.info(" - Found class [" + classInfo.getName() + "]");

                for (MethodInfo methodInfo : classInfo.getMethodInfo()) {
                    if (methodInfo.hasAnnotation(HealthCheck.class)) {

                        Method method = methodInfo.loadClassAndGetMethod();
                        if (HealthCheckAnnotationUtils.isValidHealthCheckMethod(method)) {
                            log.info(" -- Method: [" + methodInfo.getName() + "]");
                            healthCheckMethods.add(method);
                        }
                        else {
                            throw new AssertionError("Invalid @HealthCheck annotated method found: ["
                                    + methodInfo.getName() + "]. This should be fixed in code."
                                    + " Annotated methods should be of type \"void methodName(CheckSpecification)\".");
                        }
                    }
                }
            }
            return healthCheckMethods;
        }
    }
}
