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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Test that the ClassGraph scanner is able to correctly find annotated methods.
 */
public class ClassGraphHealthCheckMethodScannerTest {

    @Test
    public void testFindingHealthChecks() {
        List<Method> result =
                ClassGraphHealthCheckMethodScanner.findHealthCheckMethods(
                        Collections.singleton("com.storebrand.healthcheck.scanner.testclasses"),
                        Collections.singleton(ClassGraphHealthCheckMethodScannerTest.class.getClassLoader()));

        assertEquals(3, result.size());

        assertTrue(result.stream().anyMatch(method -> "method1".equals(method.getName())));
        assertTrue(result.stream().anyMatch(method -> "method2".equals(method.getName())));
        assertTrue(result.stream().anyMatch(method -> "method3".equals(method.getName())));
    }
}
