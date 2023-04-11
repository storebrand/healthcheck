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

import static java.util.stream.Collectors.toSet;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import com.storebrand.healthcheck.HealthCheckReportDto.EntityRefDto;

/**
 * Interface that is used to specify a health check. This is used in {@link HealthCheckRegistry#registerHealthCheck(HealthCheckMetadata,
 * Consumer)} and {@link HealthCheckRegistry#registerHealthCheck(HealthCheckMetadata, Method, Object)} in order to
 * register a health check.
 * <p>
 * When used with the registration method that takes a {@link Method} as an argument you should supply a method that
 * returns nothing and has this interface as the single argument.
 * <p>
 * The {@link CheckSpecification} is used to define one or more checks and the different {@link Axis} that these checks
 * align with. All the registered health checks will be called by the health check system, in order to capture the
 * specification steps.
 * <p>
 * Each "step" in the specification will be carried out in order when performing a health check. If we want to print
 * some static text, then perform a number of check, and then print some dynamic text based on the results of the checks
 * that is possible by calling them in the order you want, and utilize the context to pass data along to the next
 * "step".
 * <p>
 * <b>IMPORTANT NOTICE:</b> Health checks should never throw unhandled exceptions out of the user specified steps. If
 * an unhandled exception occurs when performing a health check the system will catch and display it, but as we can no
 * longer determine the outcome of the health check we have to assume the worst case scenario, and will trigger ALL
 * specified axes. Care should be made to ensure checks that may trigger {@link Axis#NOT_READY} or {@link
 * Axis#REQUIRES_REBOOT} does not accidentally crash, as this may end up taking the instance out of load balancer, or
 * even reboot it.
 * <p>
 * It is possible to update the specification of a health check after initial registration, by keeping a reference to
 * the {@link CheckSpecification} and calling the methods on the interface again. When doing so all steps must be
 * recreated, just as if calling specifying it for the first time, but you must also manually call {@link #commit()}
 * when done registering new steps in order to activate the new steps. Committing will replace all previous steps with
 * the steps entered since the last commit. Updating the specification of a health check is not a thread safe operation,
 * and as such care should be taken so multiple threads do not attempt to update the check at the same time.
 * <p>
 * Health checks are otherwise assumed to be thread safe during regular execution, and the {@link HealthCheckRegistry}
 * may execute the same health check multiple times in parallel, although it won't do that regularly unless specifically
 * ordered to create multiple synchronous health check reports at the same time.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public interface CheckSpecification {
    /**
     * Add static text to the health check result. This text will be the same each time the check runs. It can be used
     * to create headers or help text that does not need to change based on the actual status.
     *
     * @param textLine
     *         the static text we wish to add.
     * @return itself, so we can chain commands in a builder pattern way.
     */
    CheckSpecification staticText(String textLine);

    /**
     * Add dynamic text to the health check result. This text gets the shared context of this specific run of the health
     * check, and it is possible to generate different text each time the check runs.
     *
     * @param method
     *         a lambda / method that gets the {@link SharedContext} for this run of the health check, and returns the
     *         actual text we want.
     * @return {@link CheckSpecification} so we can chain commands in a builder pattern way.
     */
    CheckSpecification dynamicText(Function<SharedContext, String> method);

    /**
     * Add a link to the health check. This link will be static, and may link to a monitor page or other URL relevant
     * for this check.
     * <p>
     * Note: In text mode we will display both displayText and URL, but in HTML-mode we can choose to show the display
     * text, and "hide" the link in the &lt;a href=url&gt;displayText&lt;/a&gt;.
     *
     * @param displayText
     *         the text that shows on the link.
     * @param url
     *         the actual URL that we link to.
     * @return {@link CheckSpecification} so we can chain commands in a builder pattern way.
     */
    CheckSpecification link(String displayText, String url);

    /**
     * Create an actual check inside this health check that checks an aspect of the system, and returns a set of
     * predefined axes that are either active or not, indicating a fault or no fault.
     *
     * @param responsibleTeams
     *         the team(s) responsible for first looking into this check if it is faulty.
     * @param axes
     *         the axes that this status may trigger, worst case. You may use {@link Axis#of(Axis...)} to create this
     *         array.
     * @param method
     *         the lambda/method that performs the actual check.
     * @return {@link CheckSpecification} so we can chain commands in a builder pattern way.
     */
    CheckSpecification check(CharSequence responsibleTeams[], Axis[] axes, Function<CheckContext, CheckResult> method);

    /**
     * Convenience variant of {@link #check(CharSequence[], Axis[], Function)} if you only have a single responsible
     * team. Create an actual check inside this health check that checks an aspect of the system, and returns a set of
     * predefined axes that are either active or not, indicating a fault or no fault.
     *
     * @param responsibleTeam
     *         the team responsible for first looking into this check if it is faulty.
     * @param axes
     *         the axes that this status may trigger, worst case. You may use {@link Axis#of(Axis...)} to create this
     *         array.
     * @param method
     *         the lambda/method that performs the actual check.
     * @return {@link CheckSpecification} so we can chain commands in a builder pattern way.
     */
    default CheckSpecification check(CharSequence responsibleTeam, Axis[] axes,
            Function<CheckContext, CheckResult> method) {
        return check(Responsible.teams(responsibleTeam), axes, method);
    }

    /**
     * Only supplied for backwards compatibility. See {@link #check(Responsible, Axis[], Function)} for details.
     */
    default CheckSpecification check(Responsible responsible, Axis[] axes, Function<CheckContext, CheckResult> method) {
        return check((CharSequence) responsible, axes, method);
    }

    /**
     * Convenience variant of {@link #check(Responsible, Axis[], Function)} if you only have a single axis. Create an
     * actual check inside this health check that checks an aspect of the system, and returns a set of predefined axes
     * that are either active or not, indicating a fault or no fault.
     *
     * @param responsibleTeams
     *         the team responsible for first looking into this check if it is faulty.
     * @param singleAxis
     *         the single axis that this status may trigger, worst case. If you need multiple axis, employ the {@link
     *         #check(CharSequence, Axis[], Function)}.
     * @param method
     *         the lambda/method that performs the actual check.
     * @return {@link CheckSpecification} so we can chain commands in a builder pattern way.
     */
    default CheckSpecification check(CharSequence[] responsibleTeams, Axis singleAxis, Function<CheckContext,
            CheckResult> method) {
        return check(responsibleTeams, Axis.of(singleAxis), method);
    }

    /**
     * Convenience variant of {@link #check(CharSequence[], Axis[], Function)} if you only have a single responsible
     * team and a single axis. Create an actual check inside this health check that checks an aspect of the system, and
     * returns a set of predefined axes that are either active or not, indicating a fault or no fault.
     *
     * @param responsibleTeam
     *         the team responsible for first looking into this check if it is faulty.
     * @param singleAxis
     *         the single axis that this status may trigger, worst case. If you need multiple axis, employ the {@link
     *         #check(CharSequence, Axis[], Function)}.
     * @param method
     *         the lambda/method that performs the actual check.
     * @return {@link CheckSpecification} so we can chain commands in a builder pattern way.
     */
    default CheckSpecification check(CharSequence responsibleTeam, Axis singleAxis, Function<CheckContext,
            CheckResult> method) {
        return check(Responsible.teams(responsibleTeam), Axis.of(singleAxis), method);
    }

    /**
     * Only supplied for backwards compatibility. See {@link #check(Responsible, Axis, Function)} for details.
     */
    default CheckSpecification check(Responsible responsible, Axis singleAxis, Function<CheckContext, CheckResult> method) {
        return check((CharSequence) responsible, singleAxis, method);
    }

    /**
     * Pass structured data along with the health check result, so a machine can parse and do something with this. Use
     * this along with the {@link HealthCheckMetadata#getType()} to allow a consumer to get a more detailed status.
     * <p>
     * Using structured data requires a consumer that is aware of what this means. Any consumers that don't know how to
     * handle structured data is free to ignore it, and just show normal health check statuses based on the other
     * methods in the {@link CheckSpecification}.
     * <p>
     * Only one structured data is allowed per health check. If you want a list you could create that in the structured
     * data you pass along.
     *
     * @param method
     *         the methods that creates the structured data. This would typically return a JSON string, or some other
     *         machine-readable text such as XML or CSV.
     * @return {@link CheckSpecification} so we can chain commands in a builder pattern way.
     */
    CheckSpecification structuredData(Function<SharedContext, String> method);

    /**
     * Commits all specified steps in this specification. This is performed automatically when health checks are
     * registered by any of the {@link HealthCheckRegistry#registerHealthCheck} methods, but if you need to change the
     * health check dynamically after initial registration you can call this method.
     * <p>
     * Calling this method will copy all steps specified since startup, or since the last commit was made, and make them
     * the current steps for the check. It will then clear the temporary storage of uncommitted steps, so we can
     * re-specify the steps if needed. This will also update the current axes for the health check based on the latest
     * specification.
     * <p>
     * In short, unless you actually need to re-specify a check you normally never need to call this method.
     */
    void commit();

    /**
     * Each run of a health check creates a shared context, that is passed to all checks, dynamic text and structured
     * data methods. It is possible to add objects, such as DTOs carrying state, to the context, and get that data in
     * subsequent methods, using the {@link #get(String, Class)} method.
     */
    interface SharedContext {
        /**
         * Get an object of a specific class from the shared context.
         *
         * @param name
         *         the name of the object we want to get.
         * @param clazz
         *         the class of the object.
         * @param <T>
         *         type of object.
         * @return the object that should be in the shared context.
         */
        <T> T get(String name, Class<T> clazz);
    }

    /**
     * The shared context interface that is passed to actual {@link CheckSpecification#check(CharSequence, Axis[],
     * Function)} methods are more complex, as that allows for actually adding more text and links, and also it contains
     * the methods for creating a {@link CheckResult}. It is also possible to put data into the shared context for
     * consumption by later steps in the health check.
     */
    interface CheckContext extends SharedContext {

        /**
         * Allows creating dynamic text inside a specific check, similar to {@link CheckSpecification#dynamicText(Function)},
         * only this is local to check method, and can use whatever you have available there.
         *
         * @param text
         *         the text that we want shown in the health check.
         * @return {@link CheckContext} so we can use builder pattern for defining check.
         */
        CheckContext text(String text);

        /**
         * Create a dynamic link from inside a specific check. This could link to a specific page relevant to a fail we
         * have detected.
         *
         * @param displayText
         *         the display text for the link.
         * @param url
         *         the URL that we link to.
         * @return {@link CheckContext} so we can use builder pattern for defining check.
         */
        CheckContext link(String displayText, String url);

        /**
         * Add an exception to the health check result. This can be used to show stack traces and error messages from
         * health checks.
         *
         * @param description
         *         what the exception represents.
         * @param exception
         *         the exception.
         * @return {@link CheckContext} so we can use builder pattern for defining check.
         */
        CheckContext exception(String description, Throwable exception);

        /**
         * Add an exception to the health check result. This can be used to show stack traces and error messages from
         * health checks.
         *
         * @param exception
         *         the exception.
         * @return {@link CheckContext} so we can use builder pattern for defining check.
         */
        CheckContext exception(Throwable exception);

        /**
         * Put an object into the shared context for the health check run, such as a DTO with state.
         *
         * @param name
         *         name of the object
         * @param value
         *         the object
         * @param <T>
         *         the type of the object.
         */
        <T> void put(String name, T value);

        /**
         * Conditionally fault the health check.
         *
         * @param faulty
         *         set to true if the health check indicate the system is faulty.
         * @param description
         *         a description for the result of the health check.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        CheckResult faultConditionally(boolean faulty, String description);

        /**
         * Conditionally fault the health check, and also supply a collection of entities affected by the fault.
         * <p>
         * When supplying affected entities changes to the health status will be detected by comparing the collection of
         * entities have changed.
         *
         * @param faulty
         *         set to true if the health check indicate the system is faulty.
         * @param description
         *         a description for the result of the health check.
         * @param affectedEntities
         *         a collection of entities that is connected to a failed state.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        CheckResult faultConditionally(boolean faulty, String description, Collection<EntityRef> affectedEntities);

        /**
         * Conditionally fault the health check, and also supply a collection of entities affected by the fault.
         * <p>
         * When supplying affected entities changes to the health status will be detected by comparing the collection of
         * entities have changed.
         *
         * @param faulty
         *         set to true if the health check indicate the system is faulty.
         * @param description
         *         a description for the result of the health check.
         * @param affectedEntities
         *         an array of entities that is connected to a failed state.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        default CheckResult faultConditionally(boolean faulty, String description, EntityRef... affectedEntities) {
            return faultConditionally(faulty, description, Arrays.asList(affectedEntities));
        }

        /**
         * Conditionally fault the health check, and also supply a collection of entities affected by the fault.
         * <p>
         * When supplying a static compare string we use this to determine changes in state instead of the description.
         * This should be be used if the description may change even if the state has not really changed. An example of
         * this would be if the description contains references to how long ago an error happend, such as 10 minutes
         * ago. This will change the description over time, but the state has not really changed, so the
         * staticCompareString should remain the same.
         * <p>
         * This should only be used if you can't supply the {@link EntityRef}s that are affected by a fault.
         *
         * @param faulty
         *         set to true if the health check indicate the system is faulty.
         * @param description
         *         a description for the result of the health check.
         * @param staticCompareString
         *         a string used to compare state that should remain static as long as the state is equal.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        CheckResult faultConditionally(boolean faulty, String description, String staticCompareString);

        /**
         * Called when the check is faulty. Can be used instead of calling {@link #faultConditionally(boolean,
         * String)}.
         *
         * @param description
         *         a description for the fault.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        default CheckResult fault(String description) {
            return faultConditionally(true, description);
        }

        /**
         * Called when the check is faulty. Can be used instead of calling
         * {@link #faultConditionally(boolean, String, Collection)}
         *
         * @param description
         *         a description for the fault.
         * @param affectedEntities
         *         a collection of entities that is connected to a failed state.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        default CheckResult fault(String description, Collection<EntityRef> affectedEntities) {
            return faultConditionally(true, description, affectedEntities);
        }

        /**
         * Called when the check is faulty. Can be used instead of calling
         * {@link #faultConditionally(boolean, String, EntityRef...)}
         *
         * @param description
         *         a description for the fault.
         * @param affectedEntities
         *         an array of entities that is connected to a failed state.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        default CheckResult fault(String description, EntityRef... affectedEntities) {
            return faultConditionally(true, description, affectedEntities);
        }

        /**
         * Called when the check is faulty. Can be used instead of calling {@link #faultConditionally(boolean, String,
         * String)}
         *
         * @param description
         *         a description for the fault.
         * @param staticCompareString
         *         a string used to compare state that should remain static as long as the state is equal.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        default CheckResult fault(String description, String staticCompareString) {
            return faultConditionally(true, description, staticCompareString);
        }

        /**
         * Called when the check is OK. Can be used instead of calling {@link #faultConditionally(boolean, String)}.
         *
         * @param description
         *         a description for the check that was OK.
         * @return {@link CheckResult} that should be returned from the function specified in {@link
         * CheckSpecification#check(Responsible, Axis[], Function)}.
         */
        default CheckResult ok(String description) { // NOPMD this short name is OK
            return faultConditionally(false, description);
        }
    }

    /**
     * The result of a health check. This interface SHOULD NOT be implemented by users. The API expects specific
     * implementations of this.
     * <p>
     * This interface also contains methods for allowing adding text, links and exceptions after determining if a check
     * has failed, in a similar manner as on {@link CheckContext}.
     */
    interface CheckResult {
        /**
         * By default all axes specified will be triggered when we determine that a check has failed. However we might
         * decide that not all axes are triggered. One such example might be that we do not consider a fault to be
         * {@link Axis#CRITICAL_WAKE_PEOPLE_UP} unless the check has been in a failed state for a certain amount of
         * time. This method allows turning off such axes.
         * <p>
         * Note that we only allow turning off axes. This is intentional, as allowing turning on axes dynamically would
         * allow turning on axes that were not specified in the {@link CheckSpecification}. This would break the
         * contract that we should know ahead of time exactly which axes a health check might trigger. This is the
         * reason we default to turning on all when we fail, and only allow turning off axes.
         *
         * @param axes
         *         the axes that should be turned off.
         * @return {@link CheckResult} that should either be returned or used for chaining commands until we return it.
         */
        CheckResult turnOffAxes(Axis... axes);

        /**
         * Similar to {@link #turnOffAxes(Axis...)} only it conditionally turns of axes based on the "turnOff" argument.
         * If "turnOff" is false this method will do nothing.
         *
         * @param turnOff
         *         only turn off if this is true.
         * @param axes
         *         the axes that should be turned off.
         * @return {@link CheckResult} that should either be returned or used for chaining commands until we return it.
         */
        CheckResult turnOffAxesConditionally(boolean turnOff, Axis... axes);

        /**
         * Same as {@link CheckContext#text(String)}.
         */
        CheckResult text(String text);

        /**
         * Same as {@link CheckContext#link(String, String)}.
         */
        CheckResult link(String displayText, String url);

        /**
         * Same as {@link CheckContext#exception(String, Throwable)}.
         */
        CheckResult exception(String description, Throwable exception);

        /**
         * Same as {@link CheckContext#exception(Throwable)}.
         */
        CheckResult exception(Throwable exception);
    }

    /**
     * Represents a reference to an entity. This can be anything that has both a type and an ID connected to it, and is
     * used to signal that this specific entity is connected to a failed check.
     */
    final class EntityRef {
        private final String _type;
        private final String _id;

        @SuppressWarnings("RedundantModifier") // Not actually redundant, if I remove "public" the code won't compile.
        public EntityRef(String type, String id) {
            this._type = type;
            this._id = id;
        }

        public static EntityRef create(String type, String id) {
            return new EntityRef(type, id);
        }

        public static EntityRef create(String type, long id) {
            return new EntityRef(type, Long.toString(id));
        }

        public static Collection<EntityRef> of(String type, Stream<String> ids) { // NOPMD - This short name is fine
            return ids.map(id -> EntityRef.create(type, id)).collect(toSet());
        }

        public static Collection<EntityRef> of(String type, String... ids) { // NOPMD - This short name is fine
            return Arrays.stream(ids).map(id -> EntityRef.create(type, String.valueOf(id))).collect(toSet());
        }

        public static Collection<EntityRef> of(String type, Collection<String> ids) { // NOPMD - This short name is fine
            return ids.stream().map(id -> EntityRef.create(type, String.valueOf(id))).collect(toSet());
        }

        public EntityRefDto toDto() {
            EntityRefDto dto = new EntityRefDto();
            dto.type = _type;
            dto.id = _id;
            return dto;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            EntityRef entityRef = (EntityRef) o;
            return _type.equals(entityRef._type) && _id.equals(entityRef._id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(_type, _id);
        }

        @Override
        public String toString() {
            return "[" + _type + ":" + _id + "]";
        }
    }
}
