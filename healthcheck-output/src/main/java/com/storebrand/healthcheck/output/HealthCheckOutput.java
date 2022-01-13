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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

import com.storebrand.healthcheck.HealthCheckReportDto;

/**
 * Common interface and utility methods for outputting health check status in machine- or human-readable formats.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public interface HealthCheckOutput {

    /**
     * Write the content of the {@link HealthCheckReportDto} to the {@link Writer} object in a specified way based on
     * the implementation.
     */
    void write(HealthCheckReportDto dto, Writer out);

    default String writeToString(HealthCheckReportDto dto) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            write(dto, pw);
        }
        return sw.toString();
    }

    class HealthCheckOutputException extends RuntimeException {
        public HealthCheckOutputException(String message) {
            super(message);
        }
    }
}
