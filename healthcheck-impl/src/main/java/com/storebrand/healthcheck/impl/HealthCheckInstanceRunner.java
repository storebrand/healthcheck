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

package com.storebrand.healthcheck.impl;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.HealthCheckLogger;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckRegistry.RegisteredHealthCheck;
import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;
import com.storebrand.healthcheck.Responsible;
import com.storebrand.healthcheck.impl.HealthCheckRegistryImpl.HealthCheckResult;
import com.storebrand.healthcheck.impl.Status.StatusWithAxes;

/**
 * This is a runner that regularly runs a {@link HealthCheckInstance} asynchronously in a separate thread. It caches the
 * latest result and makes it available to others.
 * <p>
 * If a {@link HealthCheckInstance} is not async, then it will perform the check directly instead. It will still run a
 * background thread for regular updates, in order to make sure we catch state changes even if no one requests new
 * statuses from us.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class HealthCheckInstanceRunner implements RegisteredHealthCheck {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckInstanceRunner.class);

    private final HealthCheckRegistryImpl _healthCheckRegistry;
    private final HealthCheckMetadata _metadata;
    private final HealthCheckInstance _healthCheckInstance;
    private final HealthCheckLogger _healthCheckLogger;
    private final Clock _clock;

    private final CountDownLatch _latch = new CountDownLatch(1);

    private final List<CompletableFuture<HealthCheckResult>> _waitingForFreshData = new ArrayList<>();

    private Thread _thread; // Synchronized on "this"
    private volatile boolean _shouldRun = true;
    private volatile boolean _isRunning = false;

    private boolean _updateRequested = false;

    // _lastResult is only updated when we have lock on _lastResuiltUpdateLock, in order to ensure we always detect
    // changes when going from one state to the next.
    private final Object _lastResultUpdateLock = new Object();
    private volatile HealthCheckResult _lastResult;

    public HealthCheckInstanceRunner(HealthCheckRegistryImpl healthCheckRegistry, HealthCheckInstance healthCheckInstance,
            HealthCheckLogger healthCheckLogger, Clock clock) {
        _healthCheckRegistry = healthCheckRegistry;
        _metadata = healthCheckInstance.getMetadata();
        _healthCheckInstance = healthCheckInstance;
        _healthCheckLogger = healthCheckLogger;
        _clock = clock;
    }

    /**
     * Starts the background thread that will run this health check at a regular interval.
     */
    public void start() {
        synchronized (this) {
            if (_thread != null) {
                if (_thread.isAlive()) {
                    log.debug("Attempting to start a thread for HealthCheck[" + _metadata.name
                            + "], but it is already running. Leaving the thread as is.");
                    return;
                }
                else {
                    log.error("Attempting to start a thread for HealthCheck[" + _metadata.name
                            + "]. Noticed that a thread already exists, but it is not alive. Creating a new thread.");
                }
            }
            log.info(" - Starting thread for HealthCheck[" + _metadata.name + "]");
            _shouldRun = true;
            _thread = new Thread(this::run);
            _thread.setDaemon(true);
            _thread.setName("HealthCheck[" + _metadata.name + "]");
            _thread.start();
            _isRunning = true;
        }
    }

    /**
     * Stops the background thread that runs this health check, if it is running.
     */
    public void stop() {
        _shouldRun = false;

        synchronized (this) {
            if (_thread == null) {
                // Not running - ignoring
                return;
            }

            log.info(" - Stopping thread for HealthCheck[" + _metadata.name + "]");
            _thread.interrupt();
            _thread = null;
            _isRunning = false;
        }
    }

    /**
     * @return the metadata for the health check.
     */
    @Override
    public HealthCheckMetadata getMetadata() {
        return _metadata;
    }

    /**
     * @return axes defined in the health check.
     */
    @Override
    public NavigableSet<Axis> getAxes() {
        return _healthCheckInstance.getAxes();
    }

    /**
     * @return true if the HealthCheckRunner is running.
     */
    @Override
    public boolean isRunning() {
        synchronized (this) {
            return _isRunning;
        }
    }

    /**
     * @return the latest available status, or empty if this runner has not produced any status yet.
     */
    @Override
    public Optional<HealthCheckDto> getLatestStatus() {
        return Optional.ofNullable(_lastResult)
                .map(result -> HealthCheckRegistryImpl.healthCheckResultToDto(result, _clock.instant()));
    }

    public CompletableFuture<HealthCheckResult> refreshStatus() {
        synchronized (this) {
            CompletableFuture<HealthCheckResult> future = new CompletableFuture<>();

            // ?: Are we stopping?
            if (!_shouldRun) {
                // -> Yes, then we should not start waiting, as it would never complete.
                future.cancel(false);
                return future;
            }

            // Add future to list of futures that are waiting for fresh data. It will be called when the next run is
            // complete.
            _waitingForFreshData.add(future);

            // Wake up thread if it is sleeping, so we can get fresh data as fast as possible
            this.notifyAll();
            return future;
        }
    }

    public Optional<HealthCheckResult> updateStatusAndWait(int timeoutInMs) throws InterruptedException,
            TimeoutException {
        try {
            HealthCheckResult result = refreshStatus().get(timeoutInMs, TimeUnit.MILLISECONDS);
            return Optional.of(result);
        }
        catch (CancellationException e) {
            log.info("Unable to get fresh status for this check, because we were cancelled."
                    + " Assuming we are shutting down, and rethrow as InterruptedException.");
            throw new InterruptedException(// NOPMD - Don't need stacktrace for CancellationException.
                    "Waiting for fresh status on HealthCheck cancelled.");
        }
        catch (ExecutionException e) {
            // We should not really get here, as we should be able to catch exceptions before they propagate this
            // far. However, if we do get here, we log error, and let the method return empty, as we don't have any
            // result to show.
            log.error("Error when waiting for fresh status for health check.", e.getCause());
        }
        return Optional.empty();
    }

    /**
     * This requests an update for this health check. The health check is guaranteed to run at least once after calling
     * this, unless we are shutting down.
     */
    public void requestUpdate() {
        synchronized (this) {
            // ?: Are we stopping?
            if (!_shouldRun) {
                // -> Yes, then we should not start any updates or wake up anything.
                return;
            }
            // Set the flag that signals that an update has been requested.
            _updateRequested = true;
            // Wake up thread if it is sleeping, so we can get fresh data as fast as possible.
            this.notifyAll();
        }
    }

    /**
     * Gets the status of this health check. If we don't force fresh data we will get the latest cached result, unless
     * the health check is marked as not async.
     *
     * @param forceFreshData
     *         set to true in order to force running the actual health check, and not just rely on latest cached result
     * @return the latest status of this health check
     */
    public HealthCheckResult getStatus(boolean forceFreshData) {
        // :: Get latest result for this test
        HealthCheckResult lastResult = _lastResult;

        // ?: Should we always get fresh data?
        if (_metadata.sync) {
            // -> Yes, this status is not async, so we just perform the check, and return the result.
            log.debug("Performing check directly because this status is not async");
            return performHealthCheck();
        }

        // ?: Should we force fresh data?
        if (forceFreshData) {
            // -> Yes, we perform the check, and return the result.
            log.debug("Performing check directly because of forced fresh data.");
            return performHealthCheck();
        }

        // ?: Have this health check actually produced any results yet?
        if (lastResult == null) {
            // -> No, this health check has not finished producing anything yet.
            try {
                // Wait expected maximum runtime + a little slack, for this health check to produce a result.
                _latch.await(_metadata.expectedMaximumRunTimeInSeconds + 2, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                log.warn("Interrupted when waiting for health check status [" + _metadata.name + "] to be generated.",
                        e);
            }
            lastResult = _lastResult;
            // ?: Are there still no result?
            if (lastResult == null) {
                // -> Report slow health check during startup
                return createSlowStartupResult();
            }
        }

        // E-> We have determined that there is a cached result, and we should return it.
        return lastResult;
    }

    // ===== PRIVATE METHODS ===========================================================================================

    /**
     * Performs the actual health check, and updates the last result. This method will also log the result of the check
     * if it is not ok.
     *
     * @return the result of the health check.
     */
    private HealthCheckResult performHealthCheck() {
        HealthCheckResult result = _healthCheckInstance.performHealthCheck();
        updateLastResult(result);
        _latch.countDown();
        if (!result.isOk()) {
            _healthCheckLogger.logHealthCheckResult(
                    HealthCheckRegistryImpl.healthCheckResultToDto(result, _clock.instant()));
        }
        return result;
    }

    private HealthCheckResult createSlowStartupResult() {
        List<Status> statuses = new ArrayList<>();
        NavigableSet<Axis> axes = getAxes();

        statuses.add(Status.infoOnly("This health check has not created any reports since startup."));

        // ?: Does the check contain the NOT_READY axis, and as such affect readiness?
        if (axes.contains(Axis.NOT_READY)) {
            // -> Status affects readiness, and should return NOT_READY until a health check has been generated.
            StatusWithAxes notReadyStatus = Status.withAxes(Responsible.DEVELOPERS,
                            "As this health check affects readiness we mark it as NOT READY.",
                            axes)
                    .setAxis(Axis.NOT_READY, true);
            statuses.add(notReadyStatus);
        }

        // :: Check if we have been up for a long time and really should have a health check by now
        long jvmUpTime = ManagementFactory.getRuntimeMXBean().getUptime();
        // The time between each health check update should be maximum of interval + max running time. If we have
        // not gotten any health check status in twice that time since the JVM started then we assume the worst and
        // trigger all axes.
        long warnAfterMilliseconds =
                (_metadata.intervalInSeconds + _metadata.expectedMaximumRunTimeInSeconds) * 2L * 1000L;
        // ?: Should we trigger all axes?
        if (jvmUpTime > warnAfterMilliseconds) {
            // -> Yes, something is wrong, we should have a status by now.
            StatusWithAxes allWrong = Status.withAxes(Responsible.DEVELOPERS,
                            "We have not received any health status since startup, and assume worst case scenario."
                                    + " Has the async health check died?",
                            axes)
                    .setAllAxes(true);
            statuses.add(allWrong);
        }

        Instant now = _clock.instant();
        Instant startup = now.minusMillis(jvmUpTime);
        long runningTime = (long) (jvmUpTime * Math.pow(10, 6));
        return new HealthCheckResult(_metadata, statuses, null, runningTime, startup, now);
    }

    private void run() {
        // If any of the intervals are set to 0 we use the default time for the thread, so we ensure that the checks
        // runs at a regular interval even if we are not using async for the status.
        int sleepWhenOkInMs = (_metadata.intervalInSeconds > 0
                ? _metadata.intervalInSeconds
                : HealthCheckMetadata.DEFAULT_INTERVAL_IN_SECONDS) * 1000;
        int sleepWhenNotOkInMs = (_metadata.intervalWhenNotOkInSeconds > 0
                ? _metadata.intervalWhenNotOkInSeconds
                : HealthCheckMetadata.DEFAULT_INTERVAL_WHEN_NOT_OK_IN_SECONDS) * 1000;

        // ?: Is the sleep when NOT OK larger than when OK?
        if (sleepWhenNotOkInMs > sleepWhenOkInMs) {
            // -> Then we use the sleep when OK for both, as we don't want the interval to be more when not OK.
            sleepWhenNotOkInMs = sleepWhenOkInMs;
        }

        while (_shouldRun) {
            try {
                MDC.put("traceId", "HealthCheck[" + _metadata.name + "]" + generateRandomString());
                synchronized (this) {
                    // Reset update requested received before now, as we are now going to perform an update
                    _updateRequested = false;
                }

                HealthCheckResult result = performHealthCheck();

                // ?: Was the last check OK?
                int sleepTimeInMs = result.isOk()
                        // -> Yes, then we wait for next run normally.
                        ? sleepWhenOkInMs
                        // -> No, last check was not OK - wait for less time until next run.
                        : sleepWhenNotOkInMs;

                synchronized (this) {
                    // Complete all futures that waited for a result
                    _waitingForFreshData.forEach(future -> future.complete(result));
                    _waitingForFreshData.clear();

                    // ?: Has anyone requested an update while we performed the last check?
                    if (!_updateRequested) {
                        // -> No, then we can safely wait until next planned run, or until someone wakes us up.
                        waitForNextRun(sleepTimeInMs);
                    }
                }
            }
            // CHECKSTYLE IGNORE IllegalCatch FOR NEXT 1 LINES - Yes, I want to catch ALL exceptions.
            catch (Throwable ex) {
                // This thread should never die, so we catch all. Ideally we should never get here, as we catch
                // exceptions during health checks, and add that as part of the result.
                // However if we do get here, we want to ensure that we can continue processing, so we log the
                // exception, complete all futures that wait for a result, and wait for the next run.
                log.error("Unhandled exception in thread for HealthCheck[" + _metadata.name + "]: "
                        + ex.getMessage(), ex);

                synchronized (this) {
                    // Complete all futures that waited for a result exceptionally
                    _waitingForFreshData.forEach(future -> future.completeExceptionally(ex));
                    _waitingForFreshData.clear();
                    // Wait for next run
                    waitForNextRun(sleepWhenNotOkInMs);
                }
            }
        }
        log.info("HealthCheck[" + _metadata.name + "] async background thread shutdown complete.");
        // :: Cancel all futures waiting for fresh data
        synchronized (this) {
            _waitingForFreshData.forEach(future -> future.cancel(false));
            _waitingForFreshData.clear();
        }

    }

    /**
     * Wait a specified time before running again. This should only be called from within a synchronized block.
     *
     * @param sleepTimeInMs
     *         the amount of time to sleep in ms.
     */
    private void waitForNextRun(long sleepTimeInMs) {
        if (!Thread.holdsLock(this)) {
            throw new AssertionError("This should never happen! We should always have a lock here.");
        }
        // Wait until next run
        try {
            this.wait(sleepTimeInMs);
        }
        catch (InterruptedException e) {
            log.info("HealthCheck[" + _metadata.name + "] Got interrupted - assume we are shutting down.");
        }
    }

    /**
     * Updated the last result, and also determines if there was any state changes between the last and new result.
     * <p>
     * This method is synchronized in order to ensure we properly detect all changes and notify any observers. We keep
     * the publish inside the synchronized block in order to publish the updates in the correct order. The actual
     * publishing will run on a separate thread so it is safe to just notify the {@link HealthCheckRegistryImpl} here even if
     * we are inside the synchronized block.
     *
     * @param newResult
     *         the latest health check status available
     */
    private void updateLastResult(HealthCheckResult newResult) {
        // We only update when we have a lock, so we ensure that we catch all state changes.
        synchronized (_lastResultUpdateLock) {
            boolean statusHasChanged = !newResult.isEqualStatus(_lastResult);
            _lastResult = newResult;

            // ?: Has the status changed since the last result?
            if (statusHasChanged) {
                // -> Yes, then we publish the new result.
                _healthCheckRegistry.publishNewHealthCheckResult(newResult);
            }
        }
    }

    private static final String ALPHANUMERIC_CHARACTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    /**
     * Generate a random string, so our traceId will be unique (enough) for each run of the health check.
     */
    private String generateRandomString() {
        char[] randomChars = new char[7];
        for (int i = 0; i < randomChars.length; i++) {
            randomChars[i] = ALPHANUMERIC_CHARACTERS
                    .charAt(ThreadLocalRandom.current().nextInt(ALPHANUMERIC_CHARACTERS.length()));
        }
        return new String(randomChars);
    }
}
