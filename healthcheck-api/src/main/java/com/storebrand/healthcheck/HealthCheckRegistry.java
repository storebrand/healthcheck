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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;

/**
 * Interface for users to interact with health checks.
 * <p>
 * The interface is used for registering health checks, and for notifying the health check system that a health
 * check should run again, as it has probably changed. It can also be used to subscribe to events for when a health
 * check status has changed, or introspect what health checks exists in the system.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public interface HealthCheckRegistry {

    /**
     * Register a health check.
     * <p>
     * If you need late registration of a health check then, or need to modify it later, reference to the
     * {@link CheckSpecification}. This will allow you to respecify the check.
     *
     * @param metadata
     *         the {@link HealthCheckMetadata} that describes the health check.
     * @param method
     *         the method that is used to specify the health check. Should have one argument of type {@link
     *         CheckSpecification}, and return void.
     * @param instance
     *         the instance that has the method.
     */
    void registerHealthCheck(HealthCheckMetadata metadata, Method method, Object instance);

    /**
     * Register a health check.
     * <p>
     * This works the same as {@link #registerHealthCheck(HealthCheckMetadata, Consumer)}, but you can pass it any
     * method reference or lambda that conforms to the supplier, and it will use that method to specify the check.
     *
     * @param metadata
     *         the {@link HealthCheckMetadata} that describes the health check.
     * @param methodReference
     *         consumer that takes a {@link CheckSpecification} and use it to specify a health check.
     */
    void registerHealthCheck(HealthCheckMetadata metadata, Consumer<CheckSpecification> methodReference);

    /**
     * Convenience method that can be used to check if we have already registered a health check by using this specific
     * {@link Method} with {@link #registerHealthCheck(HealthCheckMetadata, Method, Object)}. This is useful for
     * avoiding re-registering the same check multiple times. {@link HealthCheckRegistry} will keep a record of all
     * methods used to specify health checks.
     *
     * @param method
     *         the {@link Method} we want to check
     * @return true if we have registered a health check by using this method before.
     */
    boolean isMethodRegistered(Method method);

    /**
     * Register a supplier of additional properties, that will be delivered with the {@link HealthCheckReportDto}.
     *
     * @param supplier
     *         implementation of supplier
     */
    void registerInfoPropertiesSupplier(InfoPropertiesSupplier supplier);

    /**
     * Start all health checks. This kicks off the background threads for the health checks. Any health checks
     * registered after this is called will be automatically started.
     */
    void startHealthChecks();

    /**
     * Returns true if health checks are currently running.
     */
    boolean isRunning();

    /**
     * Stops all health checks. This is normally called as part of {@link #shutdown()}, but it is possible to stop all
     * checks without shutting down the health check system all together.
     */
    void stopHealthChecks();

    /**
     * Shuts down the {@link HealthCheckRegistry} and any background threads for health checks. This should be called as
     * part of the application shutdown process.
     */
    void shutdown();

    /**
     * Notifies the background thread for a given health check that it should run at once, because the health check has
     * likely changed. This could be called from anywhere you make changes that you know affects a certain health
     * check.
     * <p>
     * An example would be that a user has manually fixed an issue by clicking in the UI, and you want the health check
     * updated as soon as possible to reflect these changes.
     * <p>
     * This method is thread safe, and guarantees that the background thread for the health check will run at soon as it
     * is ready, and at least once after calling this method.
     *
     * @param name
     *         the name of the check that should be updated.
     */
    void triggerUpdateForHealthCheck(String name);

    /**
     * Allows an observer to receive updates when the status of a health checks has changed. This is primarily intended
     * to detect when the status goes from OK to not OK or back to OK, or when a non-OK status changes in a significant
     * way, such as triggering different axes, or affecting other entities than before.
     * <p>
     * Note: As long as a health check remains OK it will not trigger any updates here even if the description on the
     * status changes.
     * <p>
     * All updates are published from a single worker thread, and as such the observer should not perform any IO or
     * heavy calculations as a result of getting notified, as this may cause updates to be delayed. Also, although
     * unhandled exceptions will be caught you should take care, so you do not let unhandled exceptions propagate back
     * to the publisher worker thread.
     *
     * @param observer
     *         the observer that should get notified when a health check has been updated.
     */
    void subscribeToStatusChanges(HealthCheckObserver observer);

    /**
     * @return references to all registered health checks, and the current status.
     */
    List<RegisteredHealthCheck> getRegisteredHealthChecks();

    /**
     * Generates a health check report based on parameters specified in the {@link CreateReportRequest}.
     *
     * @param createReportRequest
     *         specify what kind of report we want
     * @return the health check report in DTO format
     */
    HealthCheckReportDto createReport(CreateReportRequest createReportRequest);

    /**
     * Special method that is used to create a special report for startup, that always runs synchronously, and will only
     * query health checks that may trigger the {@link Axis#NOT_READY}. It also assumes that any health check that does
     * not trigger the {@link Axis#NOT_READY} is ready, and will stop querying them for the purpose of startup.
     * <p>
     * The report is based on calling {@link #createReport(CreateReportRequest)} with {@link
     * CreateReportRequest#getStartupStatus()}, and also exclude any check that has reported that it is ready at least
     * once since startup.
     *
     * @return the health check report for the startup status.
     */
    HealthCheckReportDto getStartupStatus();

    /**
     * Convenience method that generates a {@link HealthCheckReportDto} based on calling {@link
     * #createReport(CreateReportRequest)} with {@link CreateReportRequest#getReadinessStatus()}. This lets consumers
     * know if the system is ready to handle traffic, and will only query health checks that may trigger {@link
     * Axis#NOT_READY}.
     *
     * @return the health check report for the readiness status.
     */
    HealthCheckReportDto getReadinessStatus();

    /**
     * Convenience method that generates a {@link HealthCheckReportDto} based on calling {@link
     * #createReport(CreateReportRequest)} with {@link CreateReportRequest#getLivenessStatus()}. This lets consumers
     * know if the system is live or not, and will only query health checks that may trigger {@link
     * Axis#REQUIRES_REBOOT}.
     *
     * @return the health check report for the liveness status.
     */
    HealthCheckReportDto getLivenessStatus();

    /**
     * Convenience method similar to {@link #getReadinessStatus()} and {@link #getLivenessStatus()}, but will only query
     * reports that may trigger {@link Axis#CRITICAL_WAKE_PEOPLE_UP}. This is based on
     * {@link CreateReportRequest#getCriticalStatus()}.
     *
     * @return the health check report for the critical status.
     */
    HealthCheckReportDto getCriticalStatus();

    /**
     * An interface for receiving updates when the status of a health checks has changed. This is primarily
     * intended to detect when the status goes from OK to not OK or back to OK, or when a non-OK status changes in a
     * significant way, such as triggering different axes, or affecting other entities than before.
     * <p>
     * Note: As long as a health check remains OK it will not trigger any updates here even if the description on the
     * status changes.
     */
    interface HealthCheckObserver {
        void onHealthCheckChanged(HealthCheckDto healthCheck);
    }

    /**
     * An interface for getting status on a HealthCheck.
     */
    interface RegisteredHealthCheck {
        HealthCheckMetadata getMetadata();

        NavigableSet<Axis> getAxes();

        boolean isRunning();

        Optional<HealthCheckDto> getLatestStatus();
    }

    /**
     * Interface for supplying additional properties to the {@link HealthCheckReportDto.ServiceInfoDto} that is part of the
     * {@link HealthCheckReportDto}. These can be static or dynamic properties of your system. The method will be
     * invoked each time a report is generated, so it should run fast.
     */
    interface InfoPropertiesSupplier {
        List<InfoProperty> getAdditionalProperties();
    }

    /**
     * A class for supplying a property to the health check system, so it can be returned as part of generated reports.
     */
    class InfoProperty {
        private final String _name;
        private final String _displayName;
        private final String _value;

        private InfoProperty(String name, String displayName, String value) {
            _name = name;
            _displayName = displayName;
            _value = value;
        }

        public String getName() {
            return _name;
        }

        public Optional<String> getDisplayName() {
            return Optional.ofNullable(_displayName);
        }

        public String getValue() {
            return _value;
        }

        public static InfoProperty create(String name, String value) {
            return new InfoProperty(name, null, value);
        }

        public static InfoProperty createWithDisplayName(String name, String displayName, String value) {
            return new InfoProperty(name, displayName, value);
        }
    }

    /**
     * Request object for configuring what kind of health checks should be included in a report, and if the report
     * should force fresh data, or get data from cache.
     */
    class CreateReportRequest {
        private Collection<Axis> _axes;
        private boolean _forceFreshData = false;
        private final Set<String> _excludeChecks = new TreeSet<>();
        private final List<Predicate<RegisteredHealthCheck>> _filters = new ArrayList<>();

        /**
         * Filter health checks by {@link Axis}.
         */
        public CreateReportRequest includeOnlyChecksWithAnyOfTheseAxes(Axis... axes) {
            _axes = Arrays.asList(axes);
            return this;
        }

        /**
         * Exclude these checks, by name.
         */
        public CreateReportRequest excludeChecks(Collection<String> names) {
            _excludeChecks.addAll(names);
            return this;
        }

        /**
         * Only include health check if filter returns true
         */
        public CreateReportRequest filterChecks(Predicate<RegisteredHealthCheck> healthCheckFilter) {
            _filters.add(healthCheckFilter);
            return this;
        }

        /**
         * Force fresh data. All checks will run synchronously.
         */
        public CreateReportRequest forceFreshData(boolean forceFreshData) {
            _forceFreshData = forceFreshData;
            return this;
        }

        // ===== CONVENIENCE FACTORY METHODS ===========================================================================

        /**
         * The readiness status queries all health checks that have the axis {@link Axis#NOT_READY}. This is used by
         * load balancers to determine if we can route traffic to the service.
         */
        public static CreateReportRequest readinessStatus() {
            return new CreateReportRequest()
                    .includeOnlyChecksWithAnyOfTheseAxes(Axis.NOT_READY);
        }

        /**
         * The liveness status queries all checks that have the axis {@link Axis#REQUIRES_REBOOT}. It can be used by
         * liveness probes in order to automatically kill and restart services that are in an unrecoverable failed
         * state.
         * <p>
         * The {@link Axis#REQUIRES_REBOOT} axis should be used with care, as triggering this basically calls out
         * "REBOOT ME" and any person or system with the right privileges may do so without further notice.
         */
        public static CreateReportRequest livenessStatus() {
            return new CreateReportRequest()
                    .includeOnlyChecksWithAnyOfTheseAxes(Axis.REQUIRES_REBOOT);
        }

        /**
         * The critical status will query all checks that have the axis {@link Axis#CRITICAL_WAKE_PEOPLE_UP}. This can be
         * used by systems monitoring the service in order to determine if it is in such a critical state that people
         * should be alerted immediately.
         * <p>
         * The {@link Axis#CRITICAL_WAKE_PEOPLE_UP} axis should be used with care, as triggering it may end up causing
         * alerts to go off and literally wake people up.
         */
        public static CreateReportRequest criticalStatus() {
            return new CreateReportRequest()
                    .includeOnlyChecksWithAnyOfTheseAxes(Axis.CRITICAL_WAKE_PEOPLE_UP);
        }

        // ===== INTERNAL METHODS ======================================================================================

        boolean shouldIncludeCheck(RegisteredHealthCheck registeredHealthCheck) {
            for (Predicate<RegisteredHealthCheck> filter : _filters) {
                if (!filter.test(registeredHealthCheck)) {
                    return false;
                }
            }

            if (_excludeChecks.contains(registeredHealthCheck.getMetadata().name)) {
                return false;
            }

            if (!getAxes().isPresent()) {
                return true;
            }

            return getAxes()
                    .map(axes -> !Collections.disjoint(axes, registeredHealthCheck.getAxes()))
                    .orElse(true);
        }

        Optional<Collection<Axis>> getAxes() {
            return Optional.ofNullable(_axes);
        }

        boolean shouldForceFreshData() {
            return _forceFreshData;
        }
    }
}
