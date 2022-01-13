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

package com.storebrand.healthcheck.output;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HealthCheckTestOutputTest {

    @Test
    public void test_first_five_lines_of_stacktrace_cropping() {
        // :: Arrange
        String testText1 = "Line 1\nLine 2\nLine 3\nLine 4";
        String testText2 = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6\nLine 7";

        // :: Act
        String cropped1 = HealthCheckTextOutput.getFirstFiveLinesOfStackTrace(testText1);
        String cropped2 = HealthCheckTextOutput.getFirstFiveLinesOfStackTrace(testText2);

        // :: Assert
        assertEquals(testText1, cropped1);
        assertEquals("Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n\t(...) CHOPPED by HealthCheckOutput 2 lines",
                cropped2);
    }
}
