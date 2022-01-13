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

import java.util.Optional;

import com.storebrand.healthcheck.HealthCheckReportDto.RunStatusDto;

/**
 * Holds metadata for a health check.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
@SuppressWarnings("VisibilityModifier")
public final class HealthCheckMetadata {
    public static final int DEFAULT_INTERVAL_IN_SECONDS = 600;
    public static final int DEFAULT_INTERVAL_WHEN_NOT_OK_IN_SECONDS = 120;
    public static final int DEFAULT_SLOW_HEALTH_CHECK_IN_SECONDS = 4;

    /**
     * A human understandable name of what this method is checking. E.g. "Spring", "My Database", "File share connection"...
     */
    public final String name;
    /**
     * Allows adding a description that explains what the test actually checks.
     */
    public final String description;
    /**
     * Specifies a type for this test. This can be used to inform that we might supply a certain type of structured
     * information as part of the health check.
     */
    public final String type;
    /**
     * Specify here if issues with this health check is caused by another part of the system, and we are just reporting
     * it "On behalf of" that service, as we noticed that something "over there" was wrong.
     */
    public final String onBehalfOf;
    /**
     * All health checks by default runs asynchronously in a separate thread, and we only fetch the latest result from
     * the last run when querying for status. In some cases we want immediate data instead, and don't want to wait for
     * the next run. In this case we can set sync to true. Requests for the status of this health check will be
     * performed synchronously on the request thread, and delivered once ready.
     * <p>
     * Note that this should only be used for checks that don't do anything that might take more than a split
     * second. There should be no IO operations or heavy processing. Only disable async if the health checks does simple
     * querying of values already in memory, such as listing number of observers of an object, or other current state of
     * in memory objects.
     * <p>
     * DO NOT disable async if you query the database or read from files and such, as such operations might suddenly
     * take a lot of time, and health check queries should always respond immediately.
     * <p>
     * Note that even if you disable async we will still run a background thread that performs this check regularly,
     * because we want to catch any state change, even if no one performs any health check queries.
     */
    public final boolean sync;
    /**
     * Soft deprecated. This is kept for backwards compatibility - use {@link #sync} instead.
     */
    public final boolean async;
    /**
     * Each health check has its own background thread that updates the check status. This specifies the interval we
     * wait between each time we perform an new health check. The default is to wait 10 minutes since the last check
     * finished.
     * <p>
     * If you change this remember to also look into {@link #intervalWhenNotOkInSeconds}, as that will determine the
     * interval when the health check is not OK. If {@link #intervalWhenNotOkInSeconds} is more than {@link
     * #intervalInSeconds} then they will both use this value.
     */
    public final int intervalInSeconds;
    /**
     * When we are in a NON OK state we should update the status at a higher frequency so we can detect OK status again
     * as soon as possible. The default here is to wait only 2 minutes when the last status was not OK.
     * <p>
     * Note if this is set to a highter value than {@link #intervalInSeconds} then that value will be used instead, as
     * we don't want the interval to be larger when not OK.
     */
    public final int intervalWhenNotOkInSeconds;
    /**
     * By default, we will mark health checks as slow if they use more than 4 seconds to respond. If you know the
     * expected maximum runtime for this check in seconds you can override this by setting a value here. The check will
     * set {@link RunStatusDto#slow} to true if the check is using more time than expected.
     */
    public final int expectedMaximumRunTimeInSeconds;

    /**
     * Constructor with all arguments. Use factory methods for actual creation of {@link HealthCheckMetadata}.
     */
    private HealthCheckMetadata(String name, String description, String type, String onBehalfOf, boolean sync,
            int intervalInSeconds, int intervalWhenNotOkInSeconds, int expectedMaximumRunTimeInSeconds) {
        this.name = name;
        this.description = "".equals(description) ? null : description;
        this.type = "".equals(type) ? null : type;
        this.onBehalfOf = "".equals(onBehalfOf) ? null : onBehalfOf;
        this.sync = sync;
        this.async = !sync;
        this.intervalInSeconds = intervalInSeconds;
        this.intervalWhenNotOkInSeconds = intervalWhenNotOkInSeconds;
        if (expectedMaximumRunTimeInSeconds <= 0) {
            this.expectedMaximumRunTimeInSeconds = DEFAULT_SLOW_HEALTH_CHECK_IN_SECONDS;
        }
        else {
            this.expectedMaximumRunTimeInSeconds = expectedMaximumRunTimeInSeconds;
        }
    }

    /**
     * @return get {@link #description} as {@link Optional}, as description is optional.
     */
    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    /**
     * @return get {@link #type} as {@link Optional}, as type is optional.
     */
    public Optional<String> getType() {
        return Optional.ofNullable(type);
    }

    /**
     * @return get {@link #onBehalfOf} as {@link Optional}, as onBehalfOf is optional.
     */
    public Optional<String> getOnBehalfOf() {
        return Optional.ofNullable(onBehalfOf);
    }


    // ===== FACTORY METHODS ===========================================================================================

    /**
     * Factory method for creating a {@link HealthCheckMetadata}.
     */
    public static HealthCheckMetadata create(String name, String description, String type, String onBehalfOf,
            boolean async, int intervalInSeconds, int intervalWhenNotOkInSeconds,
            int expectedMaximumRunTimeInSeconds) {
        return new HealthCheckMetadata(name, description, type, onBehalfOf, !async, intervalInSeconds,
                intervalWhenNotOkInSeconds, expectedMaximumRunTimeInSeconds);
    }

    /**
     * Create a simple {@link HealthCheckMetadata} with default values.
     */
    public static HealthCheckMetadata create(String name) {
        return HealthCheckMetadata.builder().name(name).build();
    }

    /**
     * Create a simple {@link HealthCheckMetadata} for a health check that always runs synchronously.
     */
    public static HealthCheckMetadata createSynchronous(String name) {
        return HealthCheckMetadata.builder().name(name).sync(true).build();
    }

    /**
     * Change name of a health check, but keep all other properties the same. This is used to give each check a unique
     * name if a health check method appears in multiple instances. It will return a new {@link HealthCheckMetadata}
     * with all values kept except the name that will be replaced with "newName".
     */
    public HealthCheckMetadata withName(String newName) {
        return HealthCheckMetadata.builder(this)
                .name(newName)
                .build();
    }


    // ===== BUILDER CLASS =============================================================================================

    /**
     * Factory method for creating a {@link HealthCheckMetadataBuilder}.
     */
    public static HealthCheckMetadataBuilder builder() {
        return new HealthCheckMetadataBuilder();
    }

    /**
     * Factory method for creating a {@link HealthCheckMetadataBuilder} based on a {@link HealthCheckMetadata}.
     */
    public static HealthCheckMetadataBuilder builder(HealthCheckMetadata metadata) {
        return new HealthCheckMetadataBuilder()
                .name(metadata.name)
                .description(metadata.description)
                .type(metadata.type)
                .onBehalfOf(metadata.onBehalfOf)
                .sync(metadata.sync)
                .intervalInSeconds(metadata.intervalInSeconds)
                .intervalWhenNotOkInSeconds(metadata.intervalWhenNotOkInSeconds)
                .expectedMaximumRunTimeInSeconds(metadata.expectedMaximumRunTimeInSeconds);
    }

    /**
     * Builder class for creating a {@link HealthCheckMetadata}. We want health check metadata to be immutable, so we
     * use this builder class for making it with a builder pattern.
     */
    public static class HealthCheckMetadataBuilder {
        private String _name;
        private String _description;
        private String _type;
        private String _onBehalfOf;
        private boolean _sync = false;
        private int _intervalInSeconds = DEFAULT_INTERVAL_IN_SECONDS;
        private int _intervalWhenNotOkInSeconds = DEFAULT_INTERVAL_WHEN_NOT_OK_IN_SECONDS;
        private int _expectedMaximumRunTimeInSeconds = DEFAULT_SLOW_HEALTH_CHECK_IN_SECONDS;

        public HealthCheckMetadataBuilder() {
        }

        public HealthCheckMetadataBuilder(String name) {
            _name = name;
        }

        public HealthCheckMetadataBuilder name(String name) {
            _name = name;
            return this;
        }

        public HealthCheckMetadataBuilder description(String description) {
            _description = description;
            return this;
        }

        public HealthCheckMetadataBuilder type(String type) {
            _type = type;
            return this;
        }

        public HealthCheckMetadataBuilder onBehalfOf(String onBehalfOf) {
            _onBehalfOf = onBehalfOf;
            return this;
        }

        public HealthCheckMetadataBuilder async(boolean async) {
            _sync = !async;
            return this;
        }

        public HealthCheckMetadataBuilder sync(boolean sync) {
            _sync = sync;
            return this;
        }

        public HealthCheckMetadataBuilder intervalInSeconds(int intervalInSeconds) {
            _intervalInSeconds = intervalInSeconds;
            return this;
        }

        public HealthCheckMetadataBuilder intervalWhenNotOkInSeconds(int intervalWhenNotOkInSeconds) {
            _intervalWhenNotOkInSeconds = intervalWhenNotOkInSeconds;
            return this;
        }

        public HealthCheckMetadataBuilder expectedMaximumRunTimeInSeconds(int expectedMaximumRunTimeInSeconds) {
            _expectedMaximumRunTimeInSeconds = expectedMaximumRunTimeInSeconds;
            return this;
        }

        public HealthCheckMetadata build() {
            if (_name == null || "".equals(_name)) {
                throw new IllegalStateException("Can't build HealthCheckMetadata as we are missing name.");
            }
            return HealthCheckMetadata.create(_name, _description, _type, _onBehalfOf, !_sync,
                    _intervalInSeconds, _intervalWhenNotOkInSeconds, _expectedMaximumRunTimeInSeconds);
        }
    }
}
