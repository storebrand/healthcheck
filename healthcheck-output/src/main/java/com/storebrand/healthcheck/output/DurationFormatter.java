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

import java.time.Duration;

/**
 * A formatter for duration is not available in Java 8 >:(
 */
final class DurationFormatter {

    private DurationFormatter() {
        /* util-class, hide constructor */
    }

    /**
     * Formats the given duration to a human readable string.
     */
    static String format(Duration runningTime) {
        Duration left = runningTime;

        long days = left.toDays();
        left = left.minusDays(days);

        long hours = left.toHours();
        left = left.minusHours(hours);

        long minutes = left.toMinutes();
        left = left.minusMinutes(minutes);

        long seconds = left.getSeconds();

        if (days != 0) {
            return String.format("%sd %sh %sm %ss", days, hours, minutes, seconds);
        }
        else if (hours != 0) {
            return String.format("%sh %sm %ss", hours, minutes, seconds);
        }
        else if (minutes != 0) {
            return String.format("%sm %ss", minutes, seconds);
        }
        else {
            return String.format("%ss", seconds);
        }
    }
}