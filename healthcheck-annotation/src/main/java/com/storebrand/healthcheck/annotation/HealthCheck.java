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

package com.storebrand.healthcheck.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Function;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.HealthCheckMetadata;
import com.storebrand.healthcheck.HealthCheckReportDto.RunStatusDto;

/**
 * The purpose of a HealthCheck annotation is to easily be able to create an aggregated report of the health of
 * subsystems in the application.
 * <p>
 * Typical things to report about: Is the database server up and running? Are we able to connect to some remote service?
 * Are we experiencing unusually high load, etc.
 * <p>
 * A method using this annotation MUST have exactly one argument of type {@link CheckSpecification} and the method
 * should not return anything. The {@link CheckSpecification} is used to define one or more checks and the different
 * {@link Axis} that these checks align with. During startup all the methods will be called exactly once, in order to
 * capture all health check specifications.
 * <p>
 * It is also possible to add static or dynamic text, links and structured data to a check.
 * <p>
 * Example:
 * <pre>
 * public class MyDatabase {
 *     &#064;HealthCheck(name = &quot;Database check&quot;)
 *     public void checkDB(CheckSpecification spec) {
 *         spec.check(Responsible.DEVELOPER, context -> {
 *             if (isDatabaseUp()) {
 *                 return context.ok(&quot;Database is up&quot;);
 *             }
 *             else {
 *                 return context.fault(&quot;Database is down!&quot;);
 *             }
 *         },
 *         Axis.NOT_READY, Axis.DEGRADED_COMPLETE, Axis.CRITICAL_WAKE_PEOPLE_UP);
 *     }
 *
 *     // Or if you want to group together health check statuses:
 *     &#064;HealthCheck(name = &quot;Grouped database check&quot;)
 *     public void checkDB(CheckSpecification spec) {
 *         spec.check(Responsible.DEVELOPER, Axis.of(Axis.NOT_READY, Axis.DEGRADED_COMPLETE, Axis.CRITICAL_WAKE_PEOPLE_UP),
 *             context -> {
 *                 if (isDatabaseUp()) {
 *                 return context.ok(&quot;Database 1 is up&quot;);
 *                 }
 *                 else {
 *                     return context.fault(&quot;Database 1 is down!&quot;);
 *                 }
 *             });
 *
 *         spec.check(Responsible.DEVELOPER, Axis.DEGRADED_PARTIAL, context -> {
 *             if (isDatabaseUp()) {
 *                 return context.ok(&quot;Database 2 is up&quot;);
 *             }
 *             else {
 *                 return context.fault(&quot;Database 2 is down, but this is not a critical situation!&quot;);
 *             }
 *         });
 *
 *         // It is also possible to add static and dynamic text to a check.
 *         spec.staticText(&quot;This text will be the same each time the status is updated&quot;);
 *         spec.dynamicText(context -> &quot;This text use a lambda similar to the checks, and might change each time, but will not set any error codes or activate any axes.&quot;);
 *
 *         // If we want links we can add that both inside checks and outside. Only links inside checks can be dynamic.
 *         spec.link(&quot;Link text&quot;, &quot;http://actual.link/&quot;);
 *
 *         // We can also pass structured data. This is meant for machine readable data, and the consumer should know
 *         // how to handle it.
 *         spec.structuredData(context -> &quot;This could be some structured JSON data&quot;);
 *     }
 * }
 * </pre>
 * <p>
 * In addition to setting the name for the {@link HealthCheck} the annotation also allows setting a {@link #description}
 * that explains what this health check does.
 * <p>
 * It is also possible to specifying a {@link #type} that can be used together with the {@link
 * CheckSpecification#structuredData(Function)} in order to provide machine readable data to a consumer. A consumer that
 * knows the given type could expect a certain format on the structured data.
 * <p>
 * If this {@link HealthCheck} checks something that is the responsibility of another service it is possible to specify
 * that we are checking something &quot;on behalf of&quot; another service by using {@link #onBehalfOf}. This might be
 * if we are checking some internal consistency and determine that there is an error caused by the state in another
 * MService. Or we might be unable to function, but it is not &quot;our fault&quot;: it is because another MService is
 * not responding or performing its duties as expected.
 * <p>
 * Using this annotation inside a spring bean is both allowed and encouraged. Use the package
 * "com.storebrand:healthcheck-spring" for setting up health checks with Spring. It will auto-detecting all {@link
 * HealthCheck} annotated methods on beans, and register them as health checks.
 * <p>
 * <b>IMPORTANT NOTICE:</b> Health checks should never throw unhandled exceptions, as that will make it impossible to
 * determine the actual state of the health check. If that happens the system will assume the worst case scenario, and
 * trigger all specified axes. Be extra careful when using axes that affects flow of traffic or operations, such as
 * {@link Axis#NOT_READY} or {@link Axis#REQUIRES_REBOOT}, as accidentally triggering these axes could have negative
 * consequences.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
// Allow scanning for this annotation on runtime
@Retention(RetentionPolicy.RUNTIME)
// Use in method only
@Target(ElementType.METHOD)
public @interface HealthCheck {
    /**
     * A human understandable name of what this method is checking. E.g. "Spring", "My Database", "Apsis connection"...
     */
    String name() default "";

    /**
     * Synonym of {@link #name}, to make it possible to write {@code @HealthCheck("Name Right Here")}. Note: If {@link
     * #name} is set that will be used instead of this value.
     */
    String value() default "";

    /**
     * Allows adding a description that explains what the test actually checks.
     */
    String description() default "";

    /**
     * Specifies a type for this test. This can be used to inform that we might supply a certain type of structured
     * information as part of the health check.
     */
    String type() default "";

    /**
     * Specify here if issues with this health check is caused by one of the other MServices, and we are just reporting
     * it "On behalf of" that service.
     */
    String onBehalfOf() default "";

    /**
     * All health checks by default runs asynchronously in a separate thread, and we only fetch the latest result from
     * the last run when querying for status. In some cases we want immediate data instead, and don't want to wait for
     * the next run. In this case we can set {@link #sync()} to true. Requests for the status of this health check
     * will be performed synchronously on the request thread, and delivered once ready.
     * <p>
     * Note that this should only be used for checks that don't do anything that might, possibly, take more than a split
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
    boolean sync() default false;

    /**
     * Setting this to false will make the health check run synchronously. Note: This is soft deprecated: You should use
     * {@link #sync()} instead.
     *
     * @see HealthCheck#sync() for details on how to this works, and use that instead.
     */
    boolean async() default true;

    /**
     * Each health check has its own background thread that updates the check status. This specifies the interval we
     * wait between each time we perform an new health check. The default is to wait 10 minutes since the last check
     * finished.
     * <p>
     * If you change this remember to also look into {@link #intervalWhenNotOkInSeconds()}, as that will determine the
     * interval when the health check is not OK. If {@link #intervalWhenNotOkInSeconds()} is more than {@link
     * #intervalInSeconds()} then they will both use this value.
     */
    int intervalInSeconds() default HealthCheckMetadata.DEFAULT_INTERVAL_IN_SECONDS;

    /**
     * When we are in a NON OK state we should update the status at a higher frequency so we can detect OK status again
     * as soon as possible. The default here is to wait only 2 minutes when the last status was not OK.
     * <p>
     * Note if this is set to a highter value than {@link #intervalInSeconds()} then that value will be used instead, as
     * we don't want the interval to be larger when not OK.
     */
    int intervalWhenNotOkInSeconds() default HealthCheckMetadata.DEFAULT_INTERVAL_WHEN_NOT_OK_IN_SECONDS;

    /**
     * By default we will mark health checks as slow if they use more than 4 seconds to respond. If you know the
     * expected maximum runtime for this check in seconds you can override this by setting a value here. The check will
     * set {@link RunStatusDto#slow} to true if the check is using more time than expected.
     */
    int expectedMaximumRunTimeInSeconds() default HealthCheckMetadata.DEFAULT_SLOW_HEALTH_CHECK_IN_SECONDS;
}
