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

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.storebrand.healthcheck.HealthCheckReportDto.AxesDto;
import com.storebrand.healthcheck.HealthCheckReportDto.EntityRefDto;
import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;
import com.storebrand.healthcheck.HealthCheckReportDto.LinkDto;
import com.storebrand.healthcheck.HealthCheckReportDto.RunStatusDto;
import com.storebrand.healthcheck.HealthCheckReportDto.StatusDto;
import com.storebrand.healthcheck.HealthCheckReportDto.ThrowableHolderDto;
import com.storebrand.healthcheck.Status.HasAxes;
import com.storebrand.healthcheck.Status.StatusLink;
import com.storebrand.healthcheck.Status.StatusWithAxes;
import com.storebrand.healthcheck.Status.StatusWithThrowable;

/**
 * Implementation of the health check registry, that manages all {@link HealthCheckInstance}.
 * <p>
 * This service will spin up a {@link HealthCheckInstanceRunner} for every registered health check. Each instance runner
 * will run a thread that regularly updates the status of the health check.
 * <p>
 * The service gathers all the latest results and generates reports of the overall status of the service. It is also
 * possible to manually trigger updates of a health check, or subscribe to updates for when a status changes.
 * <p>
 * In addition to generating a report of the overall health this service also supports generating specialized reports
 * for probes that monitor the service, such as startup, readiness and liveness probes, and a probe for checking for
 * critical faults. These probes only include health checks that may trigger relevant axes, such as {@link
 * Axis#NOT_READY} for the startup and readiness probe, {@link Axis#REQUIRES_REBOOT} for the liveness probe, and {@link
 * Axis#CRITICAL_WAKE_PEOPLE_UP} for the critical status probe.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class HealthCheckRegistryImpl implements HealthCheckRegistry {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckRegistryImpl.class);

    private final Clock _clock;
    private final HealthCheckLogger _healthCheckLogger;
    private final ServiceInfo _serviceInfo;

    private final Set<Method> _healthCheckMethodsRegistered = ConcurrentHashMap.newKeySet();

    /** Map of all status check runners that have been registered */
    private final Map<String, HealthCheckInstanceRunner> _healthCheckRunners = new ConcurrentHashMap<>();

    /** Observers that should be notified if a HeathCheck status changes */
    private final CopyOnWriteArrayList<HealthCheckObserver> _healthCheckObservers = new CopyOnWriteArrayList<>();

    /** Executor that updates observers in a separate thread, in order to separate it from the health check threads */
    private final ExecutorService _healthCheckStatusUpdateExecutorService = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                thread.setName("HealthCheckStatusUpdateThread");
                thread.setUncaughtExceptionHandler((t, e) ->
                        log.error("Unhandled exception while publishing HealthCheck update"
                                + " in thread [" + t.getName() + "]: " + e.getMessage(), e));
                return thread;
            }
    );

    /** Set of checks readiness checks that have reported OK at least once for the startup probe */
    private final Set<String> _finishedStartupChecks = ConcurrentHashMap.newKeySet();

    private volatile boolean _isInitialized = false;
    private volatile boolean _healthChecksAreRunning = false;
    private volatile boolean _shutdown = false;

    // ===== Constructor ===============================================================================================

    public HealthCheckRegistryImpl(Clock clock, HealthCheckLogger healthCheckLogger, ServiceInfo serviceInfo) {
        _clock = clock;
        _healthCheckLogger = healthCheckLogger;
        _serviceInfo = serviceInfo;
    }

    // ===== Starting and stopping health checks =======================================================================

    @Override
    public void startHealthChecks() {
        synchronized (_healthCheckRunners) {
            if (_shutdown) {
                throw new IllegalStateException("HealthCheckRegistry has been shutdown.");
            }
            log.info("Starting Health Check background threads.");
            _healthCheckRunners.forEach((name, runner) -> {
                if (!runner.isRunning()) {
                    runner.start();
                }
            });
            log.info("Done starting Health Check background threads.");
            _healthChecksAreRunning = true;
            if (!_isInitialized) {
                log.info("Health Check initialized and ready for use.");
            }
            _isInitialized = true;
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (this) {
            return _healthChecksAreRunning;
        }
    }

    @Override
    public void stopHealthChecks() {
        synchronized (_healthCheckRunners) {
            log.info("Stopping Health Check background threads.");
            _healthCheckRunners.forEach((name, runner) -> runner.stop());
            log.info("Done stopping Health Check background threads.");
            _healthChecksAreRunning = false;
        }
    }

    @Override
    public void shutdown() {
        synchronized (_healthCheckRunners) {
            _shutdown = true;
            stopHealthChecks();
        }

        log.info("Shutting down Health Check Status Update Executor Service");
        // :: Shut down the executor service that updates observers
        _healthCheckStatusUpdateExecutorService.shutdown();
        try {
            if (!_healthCheckStatusUpdateExecutorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                _healthCheckStatusUpdateExecutorService.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            _healthCheckStatusUpdateExecutorService.shutdownNow();
        }
        log.info("Done shutting down Health Check Status Update Executor Service");
    }

    // ===== Registering Health checks =================================================================================

    @Override
    public void registerHealthCheck(HealthCheckMetadata metadata, Method method, Object instance) {
        Objects.requireNonNull(metadata, "Metadata for HealthCheck must be present.");
        Objects.requireNonNull(method, "Method for HealthCheck must be present.");
        Objects.requireNonNull(instance, "Instance for HealthCheck must be present.");
        synchronized (_healthCheckRunners) {
            if (_healthCheckRunners.containsKey(metadata.name)) {
                throw new IllegalArgumentException("HealthCheck with name [" + metadata.name + "] already registered.");
            }
            HealthCheckInstance healthCheckInstance = HealthCheckInstance.create(metadata, _clock, method, instance);
            createHealthCheckInstanceRunner(healthCheckInstance);
            _healthCheckMethodsRegistered.add(method);
        }
    }

    @Override
    public void registerHealthCheck(HealthCheckMetadata metadata, Consumer<CheckSpecification> methodReference) {
        Objects.requireNonNull(metadata, "Metadata for HealthCheck must be present.");
        synchronized (_healthCheckRunners) {
            if (_healthCheckRunners.containsKey(metadata.name)) {
                throw new IllegalArgumentException("HealthCheck with name [" + metadata.name + "] already registered.");
            }
            HealthCheckInstance healthCheckInstance = HealthCheckInstance.create(metadata, _clock, methodReference);
            createHealthCheckInstanceRunner(healthCheckInstance);
        }
    }

    @Override
    public boolean isMethodRegistered(Method method) {
        synchronized (_healthCheckRunners) {
            return _healthCheckMethodsRegistered.contains(method);
        }
    }

    @Override
    public List<RegisteredHealthCheck> getRegisteredHealthChecks() {
        return Collections.unmodifiableList(new ArrayList<>(_healthCheckRunners.values()));
    }

    private void createHealthCheckInstanceRunner(HealthCheckInstance instance) {
        HealthCheckInstanceRunner instanceRunner = new HealthCheckInstanceRunner(this, instance, _healthCheckLogger, _clock);
        _healthCheckRunners.put(instance.getMetadata().name, instanceRunner);
        if (_healthChecksAreRunning) {
            instanceRunner.start();
        }
    }

    // ===== Registering Properties ====================================================================================

    @Override
    public void registerInfoPropertiesSupplier(InfoPropertiesSupplier supplier) {
        _serviceInfo.addAdditionalPropertiesSupplier(supplier);
    }

    // ===== Triggering updates and subscribing to changes =============================================================

    @Override
    public void triggerUpdateForHealthCheck(String name) {
        if (!_healthCheckRunners.containsKey(name)) {
            throw new NoSuchHealthCheckException(name);
        }
        _healthCheckRunners.get(name).requestUpdate();
    }

    @Override
    public void subscribeToStatusChanges(HealthCheckObserver observer) {
        _healthCheckObservers.add(observer);
    }


    // ===== Probes ====================================================================================================

    /**
     * The startup status will check all health checks that have the {@link Axis#NOT_READY} synchronously. Once a health
     * check reports that it is ready this probe will put it in {@link #_finishedStartupChecks}, and stop querying it.
     * This is an optimization we do during startup in order to reduce the load on health checks, as we assume that once
     * a check has reported that it is ready it won't be "not ready" again with regard to startup.
     */
    @Override
    public HealthCheckReportDto getStartupStatus() {
        CreateReportRequest request = new CreateReportRequest()
                .forceFreshData(true)
                .includeOnlyChecksWithAnyOfTheseAxes(Axis.NOT_READY);

        // :: Exclude checks that have reported that they are OK.
        request.excludeChecks(_finishedStartupChecks);

        // :: Generate the report
        HealthCheckReportDto report = createReport(request);

        // :: Add all finished checks to _finishedStartupChecks
        report.healthChecks.stream()
                .filter(status -> !status.axes.activated.contains(Axis.NOT_READY))
                .forEach(status -> _finishedStartupChecks.add(status.name));

        return report;
    }

    /**
     * The readiness status queries all health checks that have the axis {@link Axis#NOT_READY}. This is used by load
     * balancers to determine if we can route traffic to the service.
     */
    @Override
    public HealthCheckReportDto getReadinessStatus() {
        return createReport(CreateReportRequest.readinessStatus());
    }

    /**
     * The liveness status queries all checks that have the axis {@link Axis#REQUIRES_REBOOT}. It can be used by
     * liveness probes in order to automatically kill and restart services that are in an unrecoverable failed state.
     * <p>
     * The {@link Axis#REQUIRES_REBOOT} axis should be used with care, as triggering this basically calls out "REBOOT
     * ME" and any person or system with the right privileges may do so without further notice.
     */
    @Override
    public HealthCheckReportDto getLivenessStatus() {
        return createReport(CreateReportRequest.livenessStatus());
    }

    /**
     * The critical status will query all checks that have the axis {@link Axis#CRITICAL_WAKE_PEOPLE_UP}. This can be
     * used by systems monitoring the service in order to determine if it is in such a critical state that people should
     * be alerted immediately.
     * <p>
     * The {@link Axis#CRITICAL_WAKE_PEOPLE_UP} axis should be used with care, as triggering it may end up causing alerts
     * to go off and literally wake people up.
     */
    @Override
    public HealthCheckReportDto getCriticalStatus() {
        return createReport(CreateReportRequest.criticalStatus());
    }

    // ===== Create the report =========================================================================================

    /**
     * Generates a health check report based on parameters specified in the {@link CreateReportRequest}.
     *
     * @param createReportRequest
     *         specify what kind of report we want
     * @return the health check report in DTO format
     */
    @Override
    public HealthCheckReportDto createReport(CreateReportRequest createReportRequest) {
        if (!_healthChecksAreRunning) {
            throw new HealthChecksNotRunningException();
        }

        HealthCheckReportDto.ServiceInfoDto serviceInfo = _serviceInfo.getServiceInfo();

        // :: Get Statuses for from all health check runners
        List<HealthCheckResult> statuses = new ArrayList<>();
        _healthCheckRunners.forEach((name, runner) -> {
            if (createReportRequest.shouldIncludeCheck(runner)) {
                statuses.add(runner.getStatus(createReportRequest.shouldForceFreshData()));
            }
        });

        return createHealthCheckReportDto(serviceInfo, statuses, createReportRequest.shouldForceFreshData());
    }

    //====================================== Health check result classes ===============================================

    /**
     * Class for storing the result of a health check. This is primarily intended for internal use.
     */
    public static class HealthCheckResult {
        private final HealthCheckMetadata _metadata;
        private final List<Status> _statuses;
        private final String _structuredData;
        private final long _runningTimeInNs; // nanoseconds
        private final Instant _checkStarted;
        private final Instant _checkCompleted;
        private final boolean _ok;
        private final boolean _slow;
        private final boolean _crashed;

        HealthCheckResult(HealthCheckMetadata metadata, List<Status> statuses, String structuredData,
                long runningTimeInNs, Instant checkStarted, Instant checkCompleted) {
            _metadata = metadata;

            _structuredData = structuredData;
            _runningTimeInNs = runningTimeInNs;
            _checkStarted = checkStarted;
            _checkCompleted = checkCompleted;
            List<Status> statusList = new ArrayList<>(statuses);

            // :: Add warning for slow health checks
            int maxBeforeSlow = _metadata.expectedMaximumRunTimeInSeconds;
            _slow = _runningTimeInNs > maxBeforeSlow * Math.pow(10, 9);
            if (_slow) {
                statusList.add(Status.withOneActiveAxis(Responsible.DEVELOPERS,
                        "Health check took more than the expected maximum of " + maxBeforeSlow + " seconds!",
                        Axis.SYS_SLOW));
            }

            // :: Add warning for crashed halth check
            _crashed = statuses.stream()
                    .filter(status -> status instanceof StatusWithThrowable)
                    .map(status -> (StatusWithThrowable) status)
                    .anyMatch(StatusWithThrowable::isUnhandled);

            // :: Set statuses and calculate if we are ok
            _statuses = Collections.unmodifiableList(statusList);
            _ok = statuses.stream().allMatch(Status::isOk) && !_slow && !_crashed;
        }

        public HealthCheckMetadata getMetadata() {
            return _metadata;
        }

        public String getName() {
            return _metadata.name;
        }

        public long getRunningTimeInNs() {
            return _runningTimeInNs;
        }

        public Instant getCheckStarted() {
            return _checkStarted;
        }

        public Instant getCheckCompleted() {
            return _checkCompleted;
        }

        public Instant staleAfter() {
            // The maximum expected time between updates is the interval plus the maximum expected runtime for a status.
            int maxExpectedTimeBetweenStatusUpdates =
                    _metadata.intervalInSeconds + _metadata.expectedMaximumRunTimeInSeconds;
            // We consider a result stale if it more than 3 times the expected time since it was generated.
            return _checkCompleted.plus(maxExpectedTimeBetweenStatusUpdates * 3L, ChronoUnit.SECONDS);
        }

        public List<Status> getStatuses() {
            return _statuses;
        }

        public long getAgeInSeconds(Instant now) {
            return Duration.between(_checkCompleted, now).getSeconds();
        }

        protected List<StatusDto> generateStatusDtos() {
            return _statuses.stream().map(HealthCheckRegistryImpl::statusToDto).collect(toList());
        }

        public boolean isOk() {
            return _ok;
        }

        public boolean isSlow() {
            return _slow;
        }

        public boolean isCrashed() {
            return _crashed;
        }

        public Map<Axis, Boolean> getAggregatedAxes() {
            return aggregateAxesMap(_statuses.stream()
                    .filter(status -> status instanceof HasAxes)
                    .map(status -> (HasAxes) status)
                    .map(HasAxes::getAxes));
       }

        public String getStructuredData() {
            return _structuredData;
        }

        /**
         * Determine if the status has changed between this and the other result. This is primarily based on checking if
         * all {@link StatusWithAxes} have equal status, as stated in {@link StatusWithAxes#isEqualStatus(StatusWithAxes)}.
         * We also check if there are any exceptions thrown, and if they differ.
         *
         * @param other
         *         the other {@link HealthCheckResult} to compare this one to.
         * @return true if the status is equal, false if status has changed.
         */
        public boolean isEqualStatus(HealthCheckResult other) {
            // ?: Is the other result null?
            if (other == null) {
                // -> Yes, then we have changed, as we are not null.
                return false;
            }

            // ?: Have the aggregated axes changed?
            if (!getAggregatedAxes().equals(other.getAggregatedAxes())) {
                // -> Yes, then something has changed - no need to check anything else.
                return false;
            }

            // :: Check all statuses with axes for differences
            List<StatusWithAxes> myStatuses = getStatuses().stream()
                    .filter(status -> status instanceof StatusWithAxes)
                    .map(status -> (StatusWithAxes) status)
                    .collect(toList());

            List<StatusWithAxes> otherStatuses = other.getStatuses().stream()
                    .filter(status -> status instanceof StatusWithAxes)
                    .map(status -> (StatusWithAxes) status)
                    .collect(toList());

            // ?: Are there an unequal number of statuses with axes?
            if (myStatuses.size() != otherStatuses.size()) {
                // -> Yes, then we have detected change.
                return false;
            }

            // E-> We have the same amount of StatusWithAxes in both results. Loop through and compare each.
            for (int i = 0; i < myStatuses.size(); i++) {
                // ?: Is the status different?
                if (!myStatuses.get(i).isEqualStatus(otherStatuses.get(i))) {
                    // -> Yes, then there is change.
                    return false;
                }
            }

            // :: Check any exceptions for differences
            List<StatusWithThrowable> myExceptions = getStatuses().stream()
                    .filter(status -> status instanceof StatusWithThrowable)
                    .map(status -> (StatusWithThrowable) status)
                    .collect(toList());

            List<StatusWithThrowable> otherExceptions = other.getStatuses().stream()
                    .filter(status -> status instanceof StatusWithThrowable)
                    .map(status -> (StatusWithThrowable) status)
                    .collect(toList());

            // ?: Are there an unequal number of exceptions?
            if (myExceptions.size() != otherExceptions.size()) {
                // -> Yes, then we have detected change.
                return false;
            }

            // E-> We have detected the same amount of exceptions (this is probably 0 in most cases). We compare each.
            for (int i = 0; i < myExceptions.size(); i++) {
                // ?: Is the exception status different?
                if (!myExceptions.get(i).isEqualStatus(otherExceptions.get(i))) {
                    // -> Yes, then there is change.
                    return false;
                }
            }

            // The statuses with axes, and any exception statuses have the same status as the other result
            return true;
        }
    }

    // ===== Convert result to DTO =====================================================================================

    private HealthCheckReportDto createHealthCheckReportDto(HealthCheckReportDto.ServiceInfoDto serviceInfo,
            List<HealthCheckResult> statuses, boolean freshData) {
        HealthCheckReportDto dto = new HealthCheckReportDto();
        dto.version = HealthCheckReportDto.HEALTH_CHECK_REPORT_DTO_VERSION;
        dto.service = serviceInfo;
        dto.synchronous = freshData;
        dto.healthChecks = healthCheckResultsToDtos(statuses);

        // :: Top level aggregate of all axes present in the report
        dto.axes = aggregateAxes(dto.healthChecks.stream()
                .map(status -> status.axes));

        // :: Determine readiness, liveness and critical status
        dto.ready = !dto.axes.activated.contains(Axis.NOT_READY);
        dto.live = !dto.axes.activated.contains(Axis.REQUIRES_REBOOT);
        dto.criticalFault = dto.axes.activated.contains(Axis.CRITICAL_WAKE_PEOPLE_UP);

        return dto;
    }

    private List<HealthCheckDto> healthCheckResultsToDtos(List<HealthCheckResult> results) {
        return results.stream().map(result -> healthCheckResultToDto(result, _clock.instant())).collect(toList());
    }

    static HealthCheckDto healthCheckResultToDto(HealthCheckResult result, Instant now) {
        HealthCheckMetadata metadata = result.getMetadata();
        HealthCheckDto dto = new HealthCheckDto();
        dto.name = metadata.name;
        dto.description = Optional.ofNullable(metadata.description);
        dto.type = Optional.ofNullable(metadata.type);
        dto.onBehalfOf = Optional.ofNullable(metadata.onBehalfOf);
        dto.axes = axesToDto(result.getAggregatedAxes());

        dto.runStatus = new RunStatusDto();
        dto.runStatus.runningTimeInNs = result.getRunningTimeInNs();
        dto.runStatus.checkStarted = result.getCheckStarted();
        dto.runStatus.checkCompleted = result.getCheckCompleted();
        dto.runStatus.staleAfter = result.staleAfter();
        dto.runStatus.stale = now.isAfter(result.staleAfter());
        // ?: Are we stale?
        if (dto.runStatus.stale) {
            // -> Yes, then add SYS_STALE axis.
            // This needs to be added here at the moment we generate the report, because it is never stale when we run
            // and generate the actual check.
            dto.axes.activated = new TreeSet<>(dto.axes.activated);
            dto.axes.activated.add(Axis.SYS_STALE);
        }
        dto.runStatus.slow = result.isSlow();
        dto.runStatus.crashed = result.isCrashed();

        dto.statuses = statusesToDtos(result.getStatuses());
        dto.structuredData = Optional.ofNullable(result.getStructuredData());
        return dto;
    }

    private static List<StatusDto> statusesToDtos(List<Status> statuses) {
        return statuses.stream().map(HealthCheckRegistryImpl::statusToDto).collect(toList());
    }

    private static StatusDto statusToDto(Status status) {
        StatusDto statusDto = new StatusDto();
        statusDto.description = status.getDescription();

        AxesDto axesDto = null;
        Responsible responsible = null;
        Collection<EntityRefDto> affectedEntities = Collections.emptyList();
        // Check if this has status properties attached:
        if (status instanceof StatusWithAxes) {
            StatusWithAxes statusWithAxes = (StatusWithAxes) status;
            axesDto = axesToDto(statusWithAxes.getAxes());
            responsible = statusWithAxes.getResponsible();
            affectedEntities = statusWithAxes.getAffectedEntities()
                    .map(entities -> entities.stream().map(CheckSpecification.EntityRef::toDto).collect(toList()))
                    .orElse(null);
        }


        ThrowableHolderDto reportException = null;
        // Check if this is a status with an exception
        if (status instanceof StatusWithThrowable) {
            StatusWithThrowable statusWithThrowable = (StatusWithThrowable) status;
            reportException = new ThrowableHolderDto(statusWithThrowable.getThrowable());
            // :? Was this an unhandled exception?
            if (statusWithThrowable.isUnhandled()) {
                // Yes -> Then we add the SYS_CRASHED axis.
                axesDto = new AxesDto();
                axesDto.specified = Collections.emptySet();
                axesDto.activated = new TreeSet<>();
                axesDto.activated.add(Axis.SYS_CRASHED);
            }
        }

        LinkDto linkDto = null;
        // Check if this is a link, and add url
        if (status instanceof StatusLink) {
            StatusLink statusLink = (StatusLink) status;
            linkDto = new LinkDto();
            linkDto.displayText = statusLink.getLinkDisplayText();
            linkDto.url = statusLink.getUrl();
        }

        statusDto.axes = Optional.ofNullable(axesDto);
        statusDto.responsible = Optional.ofNullable(responsible);
        statusDto.affectedEntities = affectedEntities;
        statusDto.exception = Optional.ofNullable(reportException);
        statusDto.link = Optional.ofNullable(linkDto);

        return statusDto;
    }

    private static AxesDto axesToDto(Map<Axis, Boolean> axes) {
        AxesDto axesDto = new AxesDto();
        axesDto.specified = new TreeSet<>(axes.keySet());
        axesDto.activated = axes.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(toSet());

        // SYS-axes are never specified directly, so we remove them from specified if they are triggered, but keep them
        // in activated.
        axesDto.specified.remove(Axis.SYS_CRASHED);
        axesDto.specified.remove(Axis.SYS_SLOW);
        axesDto.specified.remove(Axis.SYS_STALE);

        return axesDto;
    }

    private static AxesDto aggregateAxes(Stream<AxesDto> axesStream) {
        AxesDto dto = new AxesDto();
        dto.specified = new TreeSet<>();
        dto.activated = new TreeSet<>();

        axesStream.forEach(axes -> {
            dto.specified.addAll(axes.specified);
            dto.activated.addAll(axes.activated);
        });
        return dto;
    }

    private static Map<Axis, Boolean> aggregateAxesMap(Stream<Map<Axis, Boolean>> streamOfAxes) {
        final Map<Axis, Boolean> aggregatedAxes = new TreeMap<>();
        streamOfAxes.forEach(axes ->
                axes.forEach((axis, value) -> {
                    boolean existingValue = aggregatedAxes.getOrDefault(axis, false);
                    aggregatedAxes.put(axis, existingValue || value);
                }));
        return aggregatedAxes;
    }


    // ===== Notify observers about statuses that change ===============================================================

    /**
     * Publishes updates for health checks to any observers. Uses a separate thread in order to not let errors or
     * slow processing in observers propagate back to the threads performing health checks.
     */
    void publishNewHealthCheckResult(HealthCheckResult result) {
        final HealthCheckDto healthCheck = healthCheckResultToDto(result, _clock.instant());
        for (HealthCheckObserver observer : _healthCheckObservers) {
            _healthCheckStatusUpdateExecutorService.execute(() -> {
                try {
                    observer.onHealthCheckChanged(healthCheck);
                }
                // CHECKSTYLE IGNORE IllegalCatch FOR NEXT 1 LINES - Yes i want to catch ALL exceptions.
                catch (Throwable ex) {
                    log.error("Error publishing new HealthCheck status to observer [" + observer.toString()
                            + "] of class type [" + observer.getClass().getName() + "]: "
                            + ex.getMessage(), ex);
                }
            });
        }
    }

    // ===== Exceptions ================================================================================================

    /**
     * Exception thrown if trying to update a health check that does not exist.
     */
    public static final class NoSuchHealthCheckException extends RuntimeException {
        NoSuchHealthCheckException(String name) {
            super("No HealthCheck named [" + name + "] found.");
        }
    }

    /**
     * Exception thrown if attempting to {@link #createReport(CreateReportRequest)} without first starting the health
     * checks.
     */
    public static final class HealthChecksNotRunningException extends RuntimeException {
        private HealthChecksNotRunningException() {
            super("HealthChecks have not been started."
                    + " Call HealthCheckRegistry.startHealthChecks() before generating reports.");
        }
    }

}
