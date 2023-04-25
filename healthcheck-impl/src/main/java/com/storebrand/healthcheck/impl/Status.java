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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification.EntityRef;

/**
 * Top level interface for all statuses that may be returned from health checks.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public interface Status {

    String getDescription();

    default boolean isOk() {
        return true;
    }

    // ===== FACTORY METHODS FOR CREATING HEALTH CHECK STATUSES ========================================================

    /**
     * This creates a new style of health check status object that can contain axes of errors that describes the
     * different aspects of this status.
     *
     * @param responsibleTeams
     *         the "teams" that should first look at issues.
     * @param description
     *         a description for this health check status. This should describe what the issue is, and what must be done to
     *         resolve the issue.
     * @param axes
     *         the axes that this health check may trigger.
     * @return a {@link StatusWithAxes}.
     */
    static StatusWithAxes withAxes(List<CharSequence> responsibleTeams, String description, Collection<Axis> axes) {
        return new StatusWithAxes(responsibleTeams, description, axes);
    }

    static StatusWithAxes withAxes(CharSequence responsibleTeam, String description, Collection<Axis> axes) {
        return Status.withAxes(Collections.singletonList(responsibleTeam), description, axes);
    }

    static StatusWithAxes withOneActiveAxis(CharSequence responsible, String description, Axis axis) {
        return new StatusWithAxes(Collections.singletonList(responsible), description,
                Collections.singleton(axis)).setAllAxes(true);
    }

    /**
     * This creates a simple health check status object that only contains information. It is does not have any level or
     * any axes of errors. Use this going forward instead of manually creating objects when you only want to present
     * information without a level.
     *
     * @param description
     *         information that should be shown in the health check status.
     * @return a health check status object that only contains information.
     */
    static StatusInfoOnly infoOnly(String description) {
        return new StatusInfoOnly(description);
    }

    static StatusLink link(String displayText, String url) {
        return new StatusLink(displayText, url);
    }

    static StatusWithThrowable withThrowable(String description, Throwable exception) {
        return new StatusWithThrowable(description, exception, false);
    }

    static StatusWithThrowable withUnhandledException(String description, Throwable exception) {
        return new StatusWithThrowable(description, exception, true);
    }

    // ===== STATUS INTERFACES AND CLASSES =============================================================================

    /**
     * Simple interface for health checks that may contain {@link Axis}.
     */
    interface HasAxes {
        NavigableMap<Axis, Boolean> getAxes();
    }

    /**
     * Response from health checks that includes axes of errors that is used to defines the properties of the status.
     *
     * @see Axis for details on the axes that can be added.
     */
    class StatusWithAxes implements Status, HasAxes {
        private final List<CharSequence> _responsibleTeams;
        private String _description;
        private final NavigableMap<Axis, Boolean> _axes = new TreeMap<>();
        private Set<EntityRef> _affectedEntities;
        private String _staticCompareString;

        StatusWithAxes(List<CharSequence> responsibleTeams, String readableInfo, Collection<Axis> axes) {
            _responsibleTeams = responsibleTeams;
            _description = readableInfo;
            axes.forEach(axis -> _axes.put(axis, false));

            // :: Special handling of DEGRADED axes, should always add "lower" degraded levels.
            if (_axes.containsKey(Axis.DEGRADED_COMPLETE)) {
                _axes.putIfAbsent(Axis.DEGRADED_PARTIAL, false);
            }
            if (_axes.containsKey(Axis.DEGRADED_PARTIAL)) {
                _axes.putIfAbsent(Axis.DEGRADED_MINOR, false);
            }
        }

        public NavigableMap<Axis, Boolean> getAxes() {
            return _axes;
        }

        public List<CharSequence> getResponsibleTeams() {
            return _responsibleTeams;
        }

        @Override
        public String getDescription() {
            return _description;
        }

        @Override
        public boolean isOk() {
            return _axes.entrySet().stream()
                    .noneMatch(Map.Entry::getValue);
        }

        public StatusWithAxes description(String description) {
            _description = description;
            return this;
        }

        /**
         * Updates value of an {@link Axis} in this {@link StatusWithAxes}. A <code>true</code> value means that the
         * axis is in the "bad state".
         */
        public StatusWithAxes setAxis(Axis axis, boolean value) {
            if (!_axes.containsKey(axis)) {
                throw new IllegalArgumentException("Status does not contain axis " + axis.name());
            }
            _axes.put(axis, value);

            // :: Special handling of DEGRADED axes. Should always update "lower" degraded levels as well
            // ?: Did we trigger this axis?
            if (value) {
                // -> Yes, then check for degraded axes, and also set "lower" axes.
                if (axis == Axis.DEGRADED_COMPLETE) {
                    _axes.put(Axis.DEGRADED_PARTIAL, true);
                    _axes.put(Axis.DEGRADED_MINOR, true);
                }
                else if (axis == Axis.DEGRADED_PARTIAL) {
                    _axes.put(Axis.DEGRADED_MINOR, true);
                }
            }

            return this;
        }

        /**
         * Set the value on multiple axes at the same time, to either bad or not bad.
         */
        public StatusWithAxes setAxes(boolean value, Axis... axes) {
            for (Axis axis : axes) {
                setAxis(axis, value);
            }
            return this;
        }

        /**
         * Set state for all axes on this status.
         */
        public StatusWithAxes setAllAxes(boolean value) {
            for (Axis axis : getAxes().keySet()) {
                setAxis(axis, value);
            }
            return this;
        }

        /**
         * Get all entities affected by a faulty state described in this status.
         */
        public Optional<Set<EntityRef>> getAffectedEntities() {
            return Optional.ofNullable(_affectedEntities);
        }

        /**
         * Set the entities that are affected by this status. This should normally be used to indicate entities that are
         * in a faulty state.
         */
        public void setAffectedEntities(Collection<EntityRef> affectedEntities) {
            _affectedEntities = affectedEntities != null
                    ? Collections.unmodifiableSet(new HashSet<>(affectedEntities))
                    : null;
        }

        public Optional<String> getStaticCompareString() {
            return Optional.ofNullable(_staticCompareString);
        }

        public void setStaticCompareString(String staticCompareString) {
            _staticCompareString = staticCompareString;
        }

        /**
         * Compares two {@link StatusWithAxes} and determines if the status has changed between these two. State changes
         * happen if the status goes from ok to not ok, or back again, or if a not ok status changes.
         * <p>
         * The rules are as follows:
         * <ol>
         * <li>If both are ok then the status has not changed, unless the set of axes available in the status is
         * different, or the one responsible has changed.</li>
         * <li>If one is ok and the other is not ok then the status has changed.</li>
         * <li>If the triggered axes change then the status has changed.</li>
         * <li>If both are not ok, and the set of affected entities change then the status has changed.</li>
         * <li>If both are not ok, and there are no affected entities in either, but the compare string has changed,
         * then the status has changed.</li>
         * <li>If both are not ok, and there are no affected entities or compare strings, then we compare the
         * description in order to determine if the status has changed.</li>
         * </ol>
         *
         * @param other
         *         the {@link StatusWithAxes} to compare with
         * @return true if the status has changed.
         */
        public boolean isEqualStatus(StatusWithAxes other) {
            // :: Determine if the responsible teams changed
            // ?: Do we have responsible in either status?
            if (getResponsibleTeams() != null || other.getResponsibleTeams() != null) {
                // -> Yes, then check if they are different

                // ?: Is either null?
                if (getResponsibleTeams() == null || other.getResponsibleTeams() == null) {
                    // -> Yes, then one is null and the other is non-null, so this have changed.
                    return false;
                }

                // E-> Both are non null at this point

                // ?: Is the size different?
                if (getResponsibleTeams().size() != other.getResponsibleTeams().size()) {
                    // -> Yes, then they are not equal
                    return false;
                }

                // Loop through and return false if they are not equal
                for (int i = 0; i < getResponsibleTeams().size(); i++) {
                    if (!getResponsibleTeams().get(i).equals(other.getResponsibleTeams().get(i))) {
                        return false;
                    }
                }

                // E-> At this point the responsible teams are determined to be equal
            }

            // ?: Are both ok?
            if (isOk() && other.isOk()) {
                // -> Both ok, return equal status if both have the same axes.
                return getAxes().keySet().equals(other.getAxes().keySet());
            }

            // ?: Are the triggered axes equal?
            if (!getAxes().equals(other.getAxes())) {
                // -> No, then the status has changed.
                return false;
            }

            // ?: Do both we and the other have affected entities?
            if (getAffectedEntities().isPresent() && other.getAffectedEntities().isPresent()) {
                // -> Yes, then we have changed if the affected entities have changed.
                return getAffectedEntities().get().equals(other.getAffectedEntities().get());
            }

            // ?: Do only one have affected entities?
            if ((getAffectedEntities().isPresent() && !other.getAffectedEntities().isPresent())
                    || (!getAffectedEntities().isPresent() && other.getAffectedEntities().isPresent())) {
                // -> Yes, then affected entities have changed and status has changed.
                return false;
            }

            // ?: Do we have a static compare string on both?
            if ((getStaticCompareString().isPresent() && other.getStaticCompareString().isPresent())) {
                // -> Yes, then we have changed if the static compare strings are different.
                return getStaticCompareString().get().equals(other.getStaticCompareString().get());
            }

            // ?: Do only one have a static compare string?
            if ((getStaticCompareString().isPresent() && !other.getStaticCompareString().isPresent())
                    || (!getStaticCompareString().isPresent() && other.getStaticCompareString().isPresent())) {
                // -> Yes, then the static compare string has changed and status has changed.
                return false;
            }

            // E-> At this point we have determined that both statuses are not OK, and there are no changes in axes.
            // Neither this or the other status have affected entities, or a static compare string, so we compare the
            // description to determine if we are equal.

            return this.getDescription().equals(other.getDescription());
        }
    }

    /**
     * Simple status that only contains information.
     */
    class StatusInfoOnly implements Status {
        private final String description;

        StatusInfoOnly(String description) {
            this.description = description;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    /**
     * Status that contains an exception. If the exception was an unhandled exception caught during a sever status check
     * then the unhandled flag will be set.
     */
    class StatusWithThrowable implements Status, HasAxes {
        private final String _description;
        private final Throwable _throwable;
        private final boolean _unhandled;
        private final NavigableMap<Axis, Boolean> _axes;

        StatusWithThrowable(String description, Throwable throwable, boolean unhandled) {
            if (throwable == null) {
                // This can be called from user land, and we should never accept null here.
                throw new IllegalArgumentException(
                        "NULL provided for throwable with description[" + description + "], throwable CANNOT BE NULL!");
            }
            _description = description;
            _throwable = throwable;
            _unhandled = unhandled;
            NavigableMap<Axis, Boolean> axes = new TreeMap<>();
            if (_unhandled) {
                axes.put(Axis.SYS_CRASHED, true);
            }
            _axes = Collections.unmodifiableNavigableMap(axes);
        }

        @Override
        public String getDescription() {
            return _description;
        }

        @Override
        public boolean isOk() {
            return false;
        }

        public Throwable getThrowable() {
            return _throwable;
        }

        public boolean isUnhandled() {
            return _unhandled;
        }

        /**
         * Compares the Throwable, description, and the {@link #isUnhandled()} status of this and the other status.
         *
         * @param other
         *         the status to compare this with.
         * @return true if the status is equal.
         */
        public boolean isEqualStatus(StatusWithThrowable other) {
            // ?: Is it the same Throwable class?
            if (!getThrowable().getClass().equals(other.getThrowable().getClass())) {
                // -> No, so not an equal exception.
                return false;
            }

            // ?: Is the unhandled flag different?
            if (isUnhandled() != other.isUnhandled()) {
                // -> Yes, so not an equal status.
                return false;
            }

            // ?: Is the description different?
            if (!getDescription().equals(other.getDescription())) {
                // -> Yes, so not an equal status.
                return false;
            }

            // ?: Is the exception message different?
            if (!Objects.equals(getThrowable().getMessage(), other.getThrowable().getMessage())) {
                // -> Yes, so not an equal exception.
                return false;
            }

            // E-> Same exception class, unhandled status, description, message. Unless the stack trace is different we
            // regard it as an equal status.
            return getStackTrace(getThrowable())
                    .equals(getStackTrace(other.getThrowable()));
        }

        @Override
        public NavigableMap<Axis, Boolean> getAxes() {
            return _axes;
        }

        private static String getStackTrace(Throwable throwable) {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        }
    }

    /**
     * Status that contains a link. This can be a link to a monitor page or another relevant page where more details on
     * the status can be found. It may also be a link to a place where issue might be resolved.
     */
    class StatusLink implements Status {
        private final String _displayText;
        private final String _url;

        StatusLink(String displayText, String url) {
            _displayText = displayText;
            _url = url;
        }

        public String getUrl() {
            return _url;
        }

        public String getLinkDisplayText() {
            return _displayText;
        }

        @Override
        public String getDescription() {
            return _displayText + ":\n -> " + _url;
        }
    }
}
