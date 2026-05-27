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

package com.storebrand.healthcheck;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

/**
 * This is a DTO object containing the response from a health check of the entire system. It contains a list of {@link
 * HealthCheckDto}, with one entry for each HealthCheck in the system.
 * <p>
 * {@link Instant} should be serialized to standard ISO datetime format with high resolution, like this:
 * <code>"2021-11-26T09:08:58.460186100Z"</code> as provided by <code>Instant.now().toString()</code>.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
@SuppressWarnings("VisibilityModifier") // Using public fields for dto
public class HealthCheckReportDto {
    /**
     * This is current the version of the DTO - not directly linked to the version of the healthcheck library. However,
     * changing the DTO will likely result in breaking changes, and the version number for the healthcheck library
     * should be updated accordingly.
     */
    public static final String HEALTH_CHECK_REPORT_DTO_VERSION = "0.3";

    public String version;
    /** Information about this service */
    public ServiceInfoDto service;
    /** List of all health checks that are included in this report */
    public List<HealthCheckDto> healthChecks;
    /** Aggregated axes for all health checks */
    public AxesDto axes;
    /** True if this service is considered ready, based on checking for {@link Axis#NOT_READY} in {@link #axes} */
    public boolean ready;
    /** True if this service is considered live, based on checking for {@link Axis#REQUIRES_REBOOT} in {@link #axes} */
    public boolean live;
    /** True if this service has a critical fault, based on checking for {@link Axis#CRITICAL_WAKE_PEOPLE_UP} in {@link #axes} */
    public boolean criticalFault;
    /** True if this report was generated synchronous, instead of getting cached results for health checks */
    public boolean synchronous;

    /**
     * DTO container for the response from a specific health check. May contain multiple {@link StatusDto}.
     */
    public static class HealthCheckDto {
        /** Name of the health check */
        public String name;
        /** An optional description of this health check */
        public Optional<String> description = Optional.empty();
        /** An optional type that can be used to say something about what kind of health check this is, defined by users */
        public Optional<String> type = Optional.empty();
        /** If this check reports an error on behalf of another service we use this to point to where the error is */
        public Optional<String> onBehalfOf = Optional.empty();
        /** The aggregated axes for this health check. */
        public AxesDto axes;
        /** List of specific statuses reported by this health check */
        public List<StatusDto> statuses;
        /** Optional field for sending structured data to consumers. Content is entirely up to the user. */
        public Optional<String> structuredData = Optional.empty();
        /** Status about running time, and any issues encountered while running this check */
        public RunStatusDto runStatus;
        /**
         * Each health check has its own background thread that updates the check status. This specifies the interval we
         * wait between each time we perform an new health check. The default is to wait 10 minutes since the last check
         * finished. Note that this is the base configuration, and the interval might be higher if the status is non-ok.
         */
        public long intervalInNs;
        /**
         * By default, we will mark health checks as slow if they use more than 4 seconds to respond. If you know the
         * expected maximum runtime for this check in seconds you can override this by setting a value here. The check will
         * set {@link RunStatusDto#slow} to true if the check is using more time than expected.
         */
        public long expectedMaximumRunTimeInNs;
    }

    /**
     * DTO containing details about running a specific health check, such as when it ran, how long it took, and if it
     * had any issues running it.
     */
    public static class RunStatusDto {
        public long runningTimeInNs;
        public Instant checkStarted;
        public Instant checkCompleted;

        /**
         * This health check status is considered stale after this time. You should have gotten a new report by now.
         * <p>
         * Stale after is defined by the max running time {@link HealthCheckMetadata#expectedMaximumRunTimeInSeconds}
         * plus the interval between running the check {@link HealthCheckMetadata#intervalInSeconds}, and multiplying
         * that by a factor of 3. This should allow for plenty of time to perform an new check. If the check has not
         * reported anything new in this time then something is probably wrong with the check.
         */
        public Instant staleAfter;

        /**
         * Crashed is set to true if the health check throws an unhandled exception during execution. We will catch the
         * exception, and include it in the report as well. If this is set to true the report did not complete as
         * expected, and may not give a real status of what the check was supposed to report.
         * <p>
         * <b>NOTE:</b> If a health check crashes it will assume the worst possible scenario, and trigger all specjaified
         * axes. This includes any {@link Axis#NOT_READY} or {@link Axis#REQUIRES_REBOOT} specified, so take care to not
         * throw unhandled exceptions.
         */
        public boolean crashed;

        /**
         * This is set to true if the check takes more than the {@link HealthCheckMetadata#expectedMaximumRunTimeInSeconds}
         * seconds to run. If the check takes more time than expected then it should be investigated why this is
         * happening. If the check just really is slow, and can't be optimized then we should increase the expected
         * maximum running time to account for that.
         */
        public boolean slow;

        /**
         * This is set to true when generating the report if the current time is after {@link #staleAfter}. If a status
         * is stale it should be investigated why this has happened. Has the background thread running the check died?
         * Or is there something in the check that is hanging?
         */
        public boolean stale;
        /**
         * If this run did not activate any axes, and none of the other flags are set, then this is set to true. This
         * indicates that the check ran successfully, and did not find any issues.
         */
        public boolean ok;
    }

    /**
     * DTO container for a set of {@link Axis}. It contains both a set for the specified axes of a health check, and a
     * set containing the currently triggered axes. It is used both in single checks, and in aggregated reports.
     */
    public static class AxesDto {
        /** These axes were specified by the health check as possible axes that may be triggered by the check */
        public Set<Axis> specified;
        /** These axes were actually triggered by the health check. */
        public Set<Axis> activated;
    }

    /**
     * Holder for a single status from a check so it can be serialized to JSON. A health check may return multiple of
     * these.
     */
    public static class StatusDto {
        public String description;
        /** A single status has axes if it checks something, but it might just be information text */
        public Optional<AxesDto> axes = Optional.empty();
        /** If a status affects specific entities we can put a collection of them here */
        public Collection<EntityRefDto> affectedEntities;
        /** Any exception that we wish to include in the health check report */
        public Optional<ThrowableHolderDto> exception = Optional.empty();
        /** An optional link that we may include for easy navigation to relevant pages */
        public Optional<LinkDto> link = Optional.empty();
        /**
         * DEPRECATED - use responsibleTeams list instead. This will only include the first team in the team list, if
         * there are multiple responsible teams.
         * <p>
         * If this check triggers any axes this is the team responsible for looking into the issue. If there are
         * multiple teams responsible this will show the first one in the list.
         */
        public Optional<String> responsible = Optional.empty();
        /** If this check triggers any axes these are the teams responsible for looking into the issue */
        public List<String> responsibleTeams = Collections.emptyList();
    }

    /**
     * Holder for a {@link Throwable} since gson has issues converting this to json we create our own structure for
     * this.
     */
    public static class ThrowableHolderDto {
        public String className;
        public String message;
        public String stackTrace;

        public ThrowableHolderDto() {
            // No-op constructor for deserialization.
        }

        public ThrowableHolderDto(Throwable exception) {
            this.className = exception.getClass().getName();
            this.message = exception.getMessage();

            // Convert the stackTrace to string;
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            exception.printStackTrace(pw);
            stackTrace = sw.toString();
        }
    }

    /**
     * Simple DTO for referencing an entity.
     */
    public static class EntityRefDto {
        public String type;
        public String id;
    }

    /**
     * Simple DTO container for a link with display text and url.
     */
    public static class LinkDto {
        public String url;
        public String displayText;
    }

    /**
     * DTO for supplying some information about the service and the host it is running on.
     */
    public static class ServiceInfoDto {
        public HostDto host;
        public ProjectDto project;
        public int cpus;
        public String operatingSystem;
        public String runningUser;
        public MemoryDto memory;
        public LoadDto load;

        public Instant runningSince;
        public Instant timeNow;
        public List<PropertyDto> properties;

        public static class PropertyDto {
            public String name;
            public Optional<String> displayName = Optional.empty();;
            public String value;
        }

        public static class HostDto {
            public String name;
            public String primaryAddress;
        }

        public static class ProjectDto {
            public String name;
            public String version;
        }

        public static class MemoryDto {

            public long systemTotal;
            public OptionalLong systemFree = OptionalLong.empty();;
            /**
             * In VM-environments with a max, like Java's "-Xmx".
             */
            public OptionalLong heapMaxAllowed = OptionalLong.empty();
            /**
             * Process heap (total memory), or Java Heap "allocated"
             */
            public long heapAllocated;
            /**
             * Java "allocated - free"
             */
            public long heapUsed;
        }

        public static class LoadDto {
            /**
             * System load
             */
            public OptionalDouble system = OptionalDouble.empty();;
            /**
             * Process load
             */
            public OptionalDouble process = OptionalDouble.empty();;
        }
    }
}
