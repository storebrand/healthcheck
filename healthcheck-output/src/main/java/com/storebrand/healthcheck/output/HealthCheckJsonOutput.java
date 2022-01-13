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
import java.io.Writer;

import com.storebrand.healthcheck.HealthCheckReportDto;
import com.storebrand.healthcheck.serial.HealthCheckReportJsonSerializer;

/**
 * Outputs a {@link HealthCheckReportDto} as JSON to the given {@link Writer} object.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class HealthCheckJsonOutput implements HealthCheckOutput {
    private static final HealthCheckReportJsonSerializer SERIALIZER = new HealthCheckReportJsonSerializer();

    @Override
    public void write(HealthCheckReportDto dto, Writer out) {
        PrintWriter printWriter = HealthCheckOutputUtils.toPrintWriter(out); // NOPMD
        // Reason for NOPMD - We don't need to close printWriter, we are only wrapping the "out" writer, and that should
        // be called by the caller, not by us.

        String json = SERIALIZER.serialize(dto);
        printWriter.write(json);

        if (printWriter.checkError()) {
            throw new HealthCheckOutputException("Unable to write HealthCheckReport as JSON to output writer ["
                    + out + "]");
        }
    }
}
