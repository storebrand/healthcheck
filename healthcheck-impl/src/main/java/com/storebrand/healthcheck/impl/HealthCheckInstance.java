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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.Responsible;
import com.storebrand.healthcheck.impl.HealthCheckRegistryImpl.HealthCheckResult;
import com.storebrand.healthcheck.impl.Status.StatusWithAxes;


/**
 * A {@link HealthCheckInstance} is created for each registered health check. It records the {@link CheckSpecification}
 * and makes it possible to perform the actual checks. The result of the checks are returned as a {@link
 * HealthCheckResult}.
 * <p>
 * Health checks are registered programmatically by using {@link HealthCheckRegistry#registerHealthCheck} methods. If
 * you import "com.storebrand:healthcheck-annotation" it is also possible to use method annotations for registering
 * health checks.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class HealthCheckInstance implements CheckSpecification {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckInstance.class);

    private final List<Entry> _uncommittedEntries = new ArrayList<>();
    private final NavigableSet<Axis> _uncommittedAxes = new TreeSet<>();
    private final HealthCheckMetadata _metadata;
    private final Clock _clock;

    private volatile Entries _checkEntries;

    HealthCheckInstance(HealthCheckMetadata metadata, Clock clock) {
        _metadata = metadata;
        _clock = clock;
    }

    /**
     * @return the name of this health check.
     */
    String getName() {
        return _metadata.name;
    }

    /**
     * @return metadata for this health check.
     */
    HealthCheckMetadata getMetadata() {
        return _metadata;
    }

    /**
     * Performs the actual health check and returns the result as a {@link HealthCheckResult}.
     *
     * @return the result of running the health check.
     */
    HealthCheckResult performHealthCheck() {
        long startTime = System.nanoTime();
        Instant started = _clock.instant();
        List<Status> statuses = new ArrayList<>();
        String structuredData = null;
        Context context = new Context();
        try {
            for (Entry entry : getCheckEntries().getEntries()) {
                EntryRunResult entryResult = entry.run(context);
                if (entryResult.statuses != null) {
                    statuses.addAll(entryResult.statuses);
                }
                if (entryResult.structuredData != null) {
                    structuredData = entryResult.structuredData;
                }
            }
        }
        // CHECKSTYLE IGNORE IllegalCatch FOR NEXT 1 LINES: Yes I really really want to catch ALL exception here.
        catch (Exception exception) {
            statuses.add(Status.withUnhandledException("Unhandled exception caught during execution of health check",
                    exception));

            // As this health check crashed we should trigger ALL specified axes. This is because we are unable to
            // determine the actual status of the check, and should assume the worst case scenario.
            statuses.add(Status.withAxes(Responsible.DEVELOPERS,
                            "As we are unable to determine the actual outcome of this check we must\n"
                                    + "assume the worst case scenario, and trigger all specified axes",
                            getAxes())
                    .setAllAxes(true));
        }
        long runningTimeInNs = System.nanoTime() - startTime;

        return new HealthCheckResult(_metadata, statuses, structuredData, runningTimeInNs, started, _clock.instant());
    }

    /**
     * @return the axes that this health check runs along.
     */
    NavigableSet<Axis> getAxes() {
        return getCheckEntries().getAxes();
    }

    /**
     * Convenience method to check if this health check has a specific axis.
     *
     * @param axis
     *         the axis we want to check.
     * @return true if this health check has this axis.
     */
    boolean hasAxis(Axis axis) {
        return getAxes().contains(axis);
    }

    private Entries getCheckEntries() {
        if (_checkEntries == null) {
            throw new IllegalStateException("No HealthCheck entries committed! This should never happen!");
        }
        return _checkEntries;
    }

    // ===== IMPLEMENTATIONS OF CheckSpecification PUBLIC INTERFACE ====================================================
    // See Javadoc on interface for details on each of the methods and classes.

    @Override
    public CheckSpecification staticText(String textLine) {
        _uncommittedEntries.add(new StaticStatus(Status.infoOnly(textLine)));
        return this;
    }

    @Override
    public CheckSpecification dynamicText(Function<SharedContext, String> method) {
        _uncommittedEntries.add(new DynamicText(method));
        return this;
    }

    @Override
    public CheckSpecification link(String displayText, String url) {
        _uncommittedEntries.add(new StaticStatus(Status.link(displayText, url)));
        return this;
    }

    @Override
    public CheckSpecification check(CharSequence responsibleTeams[], Axis[] axes, Function<CheckContext, CheckResult> method) {
        if (axes.length == 0) {
            throw new IllegalArgumentException("A check must be able to trigger at least one axis.");
        }
        // ?: Has the user specified any system axes?
        if (Arrays.stream(axes).anyMatch(
                axis -> axis == Axis.SYS_CRASHED || axis == Axis.SYS_SLOW || axis == Axis.SYS_STALE)) {
            // -> These should never be specified in a test, so we throw.
            throw new IllegalArgumentException("A check can not contain any SYS_* axes directly.");
        }

        // TODO: Remove this when INTERNAL_INCONSISTENCY is removed
        if (Arrays.stream(axes).anyMatch(axis -> axis == Axis.INTERNAL_INCONSISTENCY)) {
            log.warn("Using deprecated Axis.INTERNAL_INCONSISTENCY - Should be replaced with Axis.INCONSISTENCY!");
        }

        _uncommittedEntries.add(new Check(Arrays.asList(responsibleTeams), method, axes));
        _uncommittedAxes.addAll(Arrays.asList(axes));

        return this;
    }

    @Override
    public CheckSpecification structuredData(Function<SharedContext, String> method) {
        _uncommittedEntries.add(new StructuredData(method));
        return this;
    }

    @Override
    public void commit() {
        Entries entries = new Entries(_uncommittedEntries, _uncommittedAxes);
        _uncommittedEntries.clear();
        _uncommittedAxes.clear();
        log.info("Committing specification for HealthCheck[" + _metadata.name
                + "] with axes : " + entries.getAxes().stream()
                .map(Axis::toString)
                .collect(Collectors.joining(", ")));
        _checkEntries = entries;
    }

    private static class Context implements CheckContext {

        private final Map<String, Object> _contextObjects = new HashMap<>();

        private StatusWithAxes _currentStatus;
        private List<Status> _currentCheckResult;

        private void setCurrentCheck(List<CharSequence> responsibleTeams, NavigableSet<Axis> axes) {
            _currentStatus = Status.withAxes(responsibleTeams, "-MISSING DESCRIPTION-", axes);
            _currentCheckResult = new ArrayList<>();
        }

        private List<Status> getResultForCurrentCheck() {
            ensureCurrentlyPerformingCheck();
            List<Status> result = _currentCheckResult;
            _currentStatus = null;
            _currentCheckResult = null;
            return result;
        }

        @Override
        public <T> T get(String name, Class<T> clazz) {
            Object obj = _contextObjects.get(name);
            return clazz.cast(obj);
        }

        @Override
        public CheckContext text(String text) {
            ensureCurrentlyPerformingCheck();
            _currentCheckResult.add(Status.infoOnly(text));
            return this;
        }

        @Override
        public CheckContext link(String displayText, String url) {
            ensureCurrentlyPerformingCheck();
            _currentCheckResult.add(Status.link(displayText, url));
            return this;
        }

        @Override
        public CheckContext exception(String description, Throwable exception) {
            ensureCurrentlyPerformingCheck();
            _currentCheckResult.add(Status.withThrowable(description, exception));
            return this;
        }

        @Override
        public CheckContext exception(Throwable exception) {
            ensureCurrentlyPerformingCheck();
            _currentCheckResult.add(Status.withThrowable("", exception));
            return this;
        }

        @Override
        public <T> void put(String name, T value) {
            _contextObjects.put(name, value);
        }

        @Override
        public CheckResult faultConditionally(boolean faulty, String description) {
            return faultConditionally(faulty, description, (Collection<EntityRef>) null);
        }

        @Override
        public CheckResult faultConditionally(boolean faulty, String description,
                Collection<EntityRef> affectedEntities) {
            ensureCurrentlyPerformingCheck();
            _currentStatus.description(description);
            _currentStatus.setAllAxes(faulty);
            _currentStatus.setAffectedEntities(affectedEntities);
            _currentCheckResult.add(_currentStatus);
            return new Result(_currentStatus, this);
        }

        @Override
        public CheckResult faultConditionally(boolean faulty, String description, String staticCompareString) {
            ensureCurrentlyPerformingCheck();
            _currentStatus.description(description);
            _currentStatus.setAllAxes(faulty);
            _currentStatus.setStaticCompareString(staticCompareString);
            _currentCheckResult.add(_currentStatus);
            return new Result(_currentStatus, this);
        }

        private void ensureCurrentlyPerformingCheck() {
            if (_currentStatus == null) {
                throw new IllegalStateException(
                        "Context not performing status check - should call setCurrentCheck first.");
            }
        }
    }

    private static final class Result implements CheckResult {
        private final StatusWithAxes _status;
        private final Context _context;

        Result(StatusWithAxes status, Context context) {
            _status = status;
            _context = context;
        }

        @Override
        public CheckResult turnOffAxes(Axis... axes) {
            _status.setAxes(false, axes);
            return this;
        }

        @Override
        public CheckResult turnOffAxesConditionally(boolean turnOff, Axis... axes) {
            if (turnOff) {
                _status.setAxes(false, axes);
            }
            return this;
        }

        @Override
        public CheckResult text(String text) {
            _context.text(text);
            return this;
        }

        @Override
        public CheckResult link(String displayText, String url) {
            _context.link(displayText, url);
            return this;
        }

        @Override
        public CheckResult exception(String description, Throwable exception) {
            _context.exception(description, exception);
            return this;
        }

        @Override
        public CheckResult exception(Throwable exception) {
            _context.exception(exception);
            return this;
        }

        List<Status> getResultForCurrentCheck() {
            return _context.getResultForCurrentCheck();
        }
    }

    // ===== CLASSES FOR REGISTERING ENTRIES FOR THE CHECK =============================================================

    private interface Entry {
        EntryRunResult run(Context context);

        default NavigableSet<Axis> getAxes() {
            return Collections.emptyNavigableSet();
        }
    }

    private static final class Check implements Entry {

        private final List<CharSequence> _responsibleTeams;
        private final Function<CheckContext, CheckResult> _method;
        private final NavigableSet<Axis> _axes;

        private Check(List<CharSequence> responsibleTeams, Function<CheckContext, CheckResult> method, Axis... axes) {
            _responsibleTeams = Collections.unmodifiableList(responsibleTeams);
            _method = method;
            _axes = Collections.unmodifiableNavigableSet(new TreeSet<>(Arrays.asList(axes)));
        }

        @Override
        public EntryRunResult run(Context context) {
            context.setCurrentCheck(_responsibleTeams, _axes);
            CheckResult result = _method.apply(context);
            // ?: Validate that the result is an instance of the expected class
            if (result instanceof Result) {
                // -> Yes, the expected implementation was present, we can extract the result.
                return new EntryRunResult(((Result) result).getResultForCurrentCheck());
            }

            throw new IllegalStateException("Result from Health Check is not an instance of expected class. "
                    + "This is a programming error. Users should not implement the CheckResult interface.");
        }

        @Override
        public NavigableSet<Axis> getAxes() {
            return _axes;
        }
    }

    private static final class StaticStatus implements Entry {
        private final Status _status;

        private StaticStatus(Status status) {
            _status = status;
        }

        @Override
        public EntryRunResult run(Context context) {
            return new EntryRunResult(_status);
        }
    }

    private static final class DynamicText implements Entry {
        private final Function<SharedContext, String> _method;

        private DynamicText(Function<SharedContext, String> method) {
            _method = method;
        }

        @Override
        public EntryRunResult run(Context context) {
            String text = _method.apply(context);
            return new EntryRunResult(Status.infoOnly(text));
        }
    }

    private static final class StructuredData implements Entry {
        private final Function<SharedContext, String> _method;

        private StructuredData(Function<SharedContext, String> method) {
            _method = method;
        }


        @Override
        public EntryRunResult run(Context context) {
            String structuredData = _method.apply(context);
            return new EntryRunResult(structuredData);
        }
    }

    // ===== CONTAINER FOR KEEPING THE ENTRIES AND AXES ================================================================

    static final class Entries {
        private final List<Entry> _entries;
        private final NavigableSet<Axis> _axes;

        Entries(List<Entry> entries, Collection<Axis> axes) {
            _entries = Collections.unmodifiableList(new ArrayList<>(entries));
            _axes = Collections.unmodifiableNavigableSet(new TreeSet<>(axes));
        }

        public List<Entry> getEntries() {
            return _entries;
        }

        public NavigableSet<Axis> getAxes() {
            return _axes;
        }
    }

    // ===== RESULT OF EACH ENTRY IN A CHECK ===========================================================================

    @SuppressWarnings("VisibilityModifier") // This is a simple DTO for transfering the result
    static final class EntryRunResult {
        final List<Status> statuses;
        final String structuredData;

        EntryRunResult(List<Status> statuses, String structuredData) {
            this.statuses = statuses;
            this.structuredData = structuredData;
        }

        EntryRunResult(Status status) {
            this(Collections.singletonList(status), null);
        }

        EntryRunResult(String structuredData) {
            this(Collections.emptyList(), structuredData);
        }

        EntryRunResult(List<Status> statuses) {
            this(statuses, null);
        }
    }

    // ===== FACTORY METHODS ===========================================================================================

    static HealthCheckInstance create(HealthCheckMetadata metadata, Clock clock,
            Consumer<CheckSpecification> specificationMethod) {
        HealthCheckInstance instance = new HealthCheckInstance(metadata, clock);
        try {
            specificationMethod.accept(instance);
            instance.commit();
            return instance;
        }
        // CHECKSTYLE IGNORE IllegalCatch FOR NEXT 1 LINES - Yes i want to catch ALL exceptions.
        catch (Exception e) {
            // Wrap the exception, and pass along.
            throw new RuntimeException("Failed to register health check named [" + metadata.name + "]: "
                    + e.getMessage(), e);
        }
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    // InvocationTargetException wraps an exception, and we do pass the cause along.
    static HealthCheckInstance create(HealthCheckMetadata metadata, Clock clock, Method method, Object instance) {
        if (method.getReturnType() != Void.TYPE) {
            throw new IllegalArgumentException("Method for registering HealthCheck should be void method.");
        }

        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 1
                || parameterTypes[0] != CheckSpecification.class) {
            throw new IllegalArgumentException(
                    "Method for registering HealthCheck should only have one parameter of type CheckSpecification");
        }

        HealthCheckInstance healthCheckInstance = new HealthCheckInstance(metadata, clock);
        Object[] params = new Object[parameterTypes.length];
        params[0] = healthCheckInstance;
        try {
            method.invoke(instance, params);
            healthCheckInstance.commit();
            return healthCheckInstance;
        }
        catch (InvocationTargetException e) {
            // Extract cause of InvocationTargetException, wrap exception and pass along.
            throw new RuntimeException("Failed to register health check named [" + metadata.name + "]: "
                    + e.getMessage(), e.getCause());
        }
        // CHECKSTYLE IGNORE IllegalCatch FOR NEXT 1 LINES - Yes i want to catch ALL exceptions.
        catch (Exception e) {
            // Wrap the exception, and pass along.
            throw new RuntimeException("Failed to register health check named [" + metadata.name + "]: "
                    + e.getMessage(), e);
        }
    }

}
