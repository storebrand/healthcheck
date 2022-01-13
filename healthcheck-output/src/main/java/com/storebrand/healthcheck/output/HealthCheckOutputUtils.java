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

/**
 * Simple internal utility class for casting og wrapping a {@link Writer}, so we end up with a {@link PrintWriter}.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
final class HealthCheckOutputUtils {

    private HealthCheckOutputUtils() {
        // Utility class
    }

    static PrintWriter toPrintWriter(Writer out) {
        PrintWriter printWriter;
        if (out instanceof PrintWriter) {
            printWriter = (PrintWriter) out;
        }
        else {
            printWriter = new PrintWriter(out);
        }
        return printWriter;
    }
}
