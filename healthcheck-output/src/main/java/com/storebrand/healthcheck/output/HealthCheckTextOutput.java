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

import static java.util.stream.Collectors.toList;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.HealthCheckReportDto;
import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;
import com.storebrand.healthcheck.HealthCheckReportDto.RunStatusDto;
import com.storebrand.healthcheck.HealthCheckReportDto.StatusDto;


/**
 * HealthCheck implementation of the old ServerStatus text output. This is provided for backwards compatibility with
 * systems that parse the text output, until they are rewritten to process JSON output from {@link
 * HealthCheckJsonOutput} instead.
 * <p>
 * This implementation will not remain backwards compatible. Improvements to the text output will be made after
 * consumers have switched to parsing JSON, as they should.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class HealthCheckTextOutput implements HealthCheckOutput {

    private static final Comparator<HealthCheckDto> STATUS_GROUP_COMPARATOR =
            Comparator.comparing(
                            (HealthCheckDto statusReportGroup) -> Level.getWorstLevel(
                                    statusReportGroup.axes.activated.stream(),
                                    Stream.of(statusReportGroup.runStatus)))
                    .reversed()
                    .thenComparing(statusReportGroup -> statusReportGroup.name);

    @Override
    public void write(HealthCheckReportDto dto, Writer out) {
        // We don't need to close printWriter, we are only wrapping the "out" writer, and that should
        // be closed by the caller, not by us.
        PrintWriter printWriter = HealthCheckOutputUtils.toPrintWriter(out);
        printHealthCheckReport(dto, printWriter);
    }

    public static void printHealthCheckReport(HealthCheckReportDto dto, PrintWriter out) {
        out.println(Level.getWorstLevel(
                dto.axes.activated.stream(),
                dto.healthChecks.stream().map(status -> status.runStatus)));

        out.println("Project name:       " + dto.service.project.name);
        out.println("Project version:    " + dto.service.project.version);
        out.println("Host name:          " + dto.service.host.name
                + " (" + dto.service.host.primaryAddress + ")");

        dto.service.properties.forEach(property ->
                out.printf("%-20s%s\n", property.displayName.orElse(property.name) + ":", property.value));

        Instant jvmStartTime = dto.service.runningSince;
        LocalDateTime startTime = LocalDateTime.ofInstant(jvmStartTime, ZoneId.systemDefault());
        String startDateTime = startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        Instant nowInstant = dto.service.timeNow;
        LocalDateTime nowTime = LocalDateTime.ofInstant(nowInstant, ZoneId.systemDefault());
        Duration runningTime = Duration.between(dto.service.runningSince, dto.service.timeNow);
        String uptime = DurationFormatter.format(runningTime);
        out.println("Time now:           " + nowTime.format(DateTimeFormatter.ISO_DATE_TIME));
        out.println("Running since:      " + startDateTime + " (" + uptime + ")");
        out.println("Data freshness:     " + (dto.synchronous
                ? "Updated synchronous"
                : "Retrieved from cache (Updates async in background threads)"));

        for (HealthCheckDto healthCheck : dto.healthChecks.stream()
                .sorted(STATUS_GROUP_COMPARATOR).collect(toList())) {
            out.printf("%n-- %s: %s (%.3fms) --%n",
                    healthCheck.name,
                    Level.getWorstLevel(healthCheck.axes.activated.stream(), Stream.of(healthCheck.runStatus)),
                    healthCheck.runStatus.runningTimeInNs / Math.pow(10, 6));
            out.println(getBodyFromHealthCheck(healthCheck));
        }
    }

    /**
     * Converts a {@link HealthCheckDto} to a string. Used in the old text version of serverStatus, and also for
     * logging purporses.
     *
     * @param healthCheck
     */
    public static String getBodyFromHealthCheck(HealthCheckDto healthCheck) {
        List<StatusDto> statuses = healthCheck.statuses;
        // Construct the body, thus handling multiple lines in a single status' description.
        String[] bodyLines = statuses.stream()
                .map(statusLine -> Optional.ofNullable(statusLine.description)
                        .orElse("MISSING DESCRIPTION OF STATUS LINE!"))
                .collect(Collectors.joining("\n", "", "\n"))
                .split("\n");

        // Find largest line, to align the "status.level()" on the right side for easier human viewing
        // "Crop" evaluationat 120 chars.
        int maxDescriptionLength = Stream.of(bodyLines)
                .mapToInt(String::length)
                .map(i -> Math.min(i, 120))
                .max()
                .orElse(0);

        String body = statuses.stream()
                .map(statusDto -> {
                    String statusLine = getPaddingBetweenDescriptionAndLevel(
                            Optional.ofNullable(statusDto.description)
                                    .orElse("MISSING DESCRIPTION OF STATUS LINE!"),
                            maxDescriptionLength,
                            statusDto.axes.map(axesDto -> Level.getWorstLevelForAxes(axesDto.activated).toString())
                                    .orElse(null));

                    if (statusDto.axes.isPresent()) {
                        String activeAxes = statusDto.axes.get().activated
                                .stream()
                                .map(Axis::name)
                                .collect(Collectors.joining(", "));
                        statusLine += !"".equals(activeAxes)
                                ? " (" + activeAxes + ")"
                                : "";
                    }

                    // ?: If we have an exception added we should add a new line with more info
                    if (statusDto.exception.isPresent()) {
                        // -> Yes, we have an exception here

                        // ?: Is the description "" (empty string), implying that they used the method variant without
                        // description, only exception?
                        if ("".equals(statusLine)) {
                            // -> Yes, empty string: Output the stacktrace right away
                            statusLine = "[EXCEPTION]: ";
                        }
                        else {
                            // -> No, not empty string: Output the description, and then the stack trace on next line.
                            statusLine = "[EXCEPTION]: " + statusLine + "\n StackTrace: ";
                        }

                        // .. then add the stacktrace.
                        statusLine += getFirstFiveLinesOfStackTrace(statusDto.exception.get().stackTrace);
                    }
                    return statusLine;
                })
                // Ignore any empty lines in output
                .filter(statusLine -> !"".equals(statusLine))
                .collect(Collectors.joining("\n", "", "\n"));

        // ?: Is this status stale?
        if (healthCheck.runStatus.stale) {
            String warning = "This status is STALE. It has not been updated since "
                    + LocalDateTime.ofInstant(healthCheck.runStatus.checkCompleted, ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            body = getPaddingBetweenDescriptionAndLevel(warning, maxDescriptionLength, "WARN") + "\n" + body;
        }

        return body;
    }

    /**
     * Get the first 5 lines of a stack trace.
     */
    static String getFirstFiveLinesOfStackTrace(String stringToGetLinesFrom) {
        List<String> allLines = Arrays.asList(stringToGetLinesFrom.split("\\n"));
        int linesRemoved = (allLines.size() - 5);
        String toReturn = allLines.stream().limit(5).collect(Collectors.joining("\n"));

        // ?: Did we remove any lines?
        if (linesRemoved > 0) {
            // -> Yes, we have some lines removed so inform about this
            toReturn += "\n\t(...) CHOPPED by HealthCheckOutput " + linesRemoved + " lines";
        }

        return toReturn;
    }

    static String getPaddingBetweenDescriptionAndLevel(String description, int padding, String level) {
        // We only want to add padding if we have a level set
        if (level == null) {
            // No-> We do not have a level so no need of adjusting the padding between the description and level.
            return description;
        }

        // E-> Yes, we have a level here to show behind the description.
        // If someone is stupid enough to give a null-value for description, just output "null".
        String descriptionNonNull = Optional.ofNullable(description).orElse("MISSING DESCRIPTION OF STATUS LINE!");
        StringBuilder b = new StringBuilder();
        b.append(descriptionNonNull);
        // To align the statuses we add some padding after $description.
        for (int i = descriptionNonNull.length(); i < padding; i++) {
            b.append(" ");
        }
        b.append(": ").append(level);
        return b.toString();
    }

    /**
     * Copy of the Status.Level enum for backwards compatibility.
     */
    enum Level {
        /**
         * Used when everything is A-OKAY!
         */
        OK,
        /**
         * Used when something is wrong, but the application is still going strong without fixing it. E.g. a third-party
         * service is down, but the application works as expected. Note that SKAGEN Deploy will carry on when server
         * status gives a WARN. Use {@link #ERROR} To signal a failure which forces SKAGEN Deploy to stop.
         */
        WARN,
        /**
         * Used when something is seriously wrong. If there are no {@link Axis} attached to the Status then SKAGEN
         * Deploy and Hatchery will stop its deploy procedure. This is to remain backwards compatible.
         * <p>
         * With the introduction of Axes of errors we also check if there are {@link Axis} attached during startup. With
         * properties it will only stop if it finds {@link Axis#NOT_READY}.
         */
        ERROR;

        boolean isWorseThan(Level other) {
            if (this == ERROR && (other == WARN || other == OK)) {
                return true;
            }
            return this == WARN && other == OK;
        }

        /**
         * @return Worst level OR ok if an empty list is given
         */
        static Level getWorstStatusLevel(Stream<Level> levels) {
            return levels.reduce(Level.OK, BinaryOperator.maxBy(Comparator.naturalOrder()));
        }

        /**
         * @param axis
         *         the axis we want a legacy status level for
         * @return a legacy {@link Level} for the given axis.
         */
        static Level getLevelForAxis(Axis axis) {
            // ?: Is this one of the axes that should cause error?
            return axis == Axis.DEGRADED_COMPLETE
                    || axis == Axis.CRITICAL_WAKE_PEOPLE_UP
                    || axis == Axis.REQUIRES_REBOOT
                    || axis == Axis.SYS_CRASHED
                    // -> Yes, then return ERROR
                    ? Level.ERROR
                    // -> No, the rest are just mapped to WARN.
                    : Level.WARN;
        }

        /**
         * @param runStatus
         *         the status of a specific run of a health check
         * @return a legacy {@link Level} for the given run status.
         */
        static Level getLevelForRunStatus(RunStatusDto runStatus) {
            if (runStatus.crashed) {
                return Level.ERROR;
            }
            return runStatus.stale || runStatus.slow
                    ? Level.WARN
                    : Level.OK;
        }

        /**
         * @param axes
         *         a set of triggered axes
         * @return the worst level among the axes that are triggered.
         */
        public static Level getWorstLevelForAxes(Set<Axis> axes) {
            return getWorstStatusLevel(axes.stream()
                    .map(Level::getLevelForAxis));
        }

        /**
         * @param axes
         *         a stream of triggered axes
         * @param runStatus
         *         a stream of runStatuses for health checks
         * @return the worst level among the axes and run statuses
         */
        public static Level getWorstLevel(Stream<Axis> axes, Stream<RunStatusDto> runStatus) {
            return getWorstStatusLevel(
                    Stream.concat(axes.map(Level::getLevelForAxis),
                            runStatus.map(Level::getLevelForRunStatus)));
        }
    }
}
