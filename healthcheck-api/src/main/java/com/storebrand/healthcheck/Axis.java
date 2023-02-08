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

import java.util.function.Function;

/**
 * Axes that define the type of health check that is being reported in a more fine-grained way. These axes can be used
 * by automated tools, in order to determine if we should consider a service ready, if it is in a critical state, if we
 * should "wake up" people, or if this is self-healing or not. It can also be used by MaM to display this type of
 * information.
 * <p>
 * A good description text should still follow the status of a health check, so we know exactly how to respond to any
 * issues.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public enum Axis {
    /**
     * This error will not go away by itself. A human must look at this and take action. When using this the description
     * text should inform the human of the next steps that must be taken in order to resolve this issue. Typically, this
     * might involve going into the UI of the service and perform some kind of action.
     * <p>
     * Note: This might not be critical. If it is critical then use {@link #CRITICAL_WAKE_PEOPLE_UP} along with this.
     */
    MANUAL_INTERVENTION_REQUIRED,

    /**
     * The service is down! It can not perform its job. Examples of this can be "Database connection down", "Network
     * connection down", "Connection to MQ down", etc. The common denominator is that the service is unavailable to do
     * anything productive. It is completely degraded.
     * <p>
     * Note: This might still be self-healing and temporary, and might not require human intervention at once. That is
     * why we have {@link #MANUAL_INTERVENTION_REQUIRED} and {@link #CRITICAL_WAKE_PEOPLE_UP} for when those are
     * required.
     */
    DEGRADED_COMPLETE,

    /**
     * The service is degraded, and parts of the service does not work as intended. This might be an external
     * integration that is down, but not critical for the main function of the service. The service should still be able
     * to perform its primary task.
     */
    DEGRADED_PARTIAL,

    /**
     * A minor part of the service is down, but most of the service is still working as it should.
     */
    DEGRADED_MINOR,

    /**
     * This is a critical error that must be handled immediately. Sound the alarms! Wake people up! Get things moving.
     * <p>
     * If you add this to a health check you should also add a very good description of why this is critical, and what
     * should be done to remedy the situation.
     * <p>
     * Do not add this unless it actually requires immediate attention. Like, you do not put this on obviously
     * non-critical services, in the middle of the night, unless you want some angry people at the door next morning.
     */
    CRITICAL_WAKE_PEOPLE_UP,

    /**
     * <b>Note: This has been SOFT DEPRECATED.</b> Do not use this. Use the more generic {@link #INCONSISTENCY} instead.
     * It is kept purely for backwards compatibility, and will be removed in future versions.
     * <p>
     * <b>Also note</b>: adding this axis to a check will also add {@link #INCONSISTENCY}, and vice versa.
     * <p>
     * This should be added to a health check if the status indicate that an internal consistency check has failed. A
     * good description of what has failed should point the user in the right direction of how this can be fixed.
     */
    INTERNAL_INCONSISTENCY,

    /**
     * This should be added to a health check if the status indicate that a consistency check has failed. A good
     * description of what has failed should point the user in the right direction of how this can be fixed.
     * <p>
     * If the consistency check concerns data external to the service you can also add {@link #EXTERNAL}.
     */
    INCONSISTENCY,

    /**
     * This indicates that the problem is an external service. Something outside this service is not working as we
     * expect, and this causes issues for us. This can be used together with {@link #DEGRADED_COMPLETE}, {@link
     * #DEGRADED_PARTIAL} or {@link #DEGRADED_MINOR} to describe if there are any degraded performance as a result of
     * this external issue.
     */
    EXTERNAL,

    /**
     * This issue directly affects customer experience. This is very relevant for user facing interfaces, but also for
     * backend services that serve data that directly affects user experience.
     * <p>
     * Any error that directly affects customers should be handled as soon as possible. This might be an incident that
     * requires followup.
     * <p>
     * Note that if this is a critical error affecting customers you should consider also adding {@link
     * #CRITICAL_WAKE_PEOPLE_UP}. However, if the error occurs at night it might be wise to wait until the morning before
     * triggering {@link #CRITICAL_WAKE_PEOPLE_UP}, unless the error REALLY is so severe that it really needs to be fixed
     * in the middle of the night.
     */
    AFFECTS_CUSTOMERS,

    /**
     * This error is not a system or programming error, but rather a process error. This might be something such as
     * missing payments, or there is something wrong with data that has been entered into the system by a user. This is
     * an error on the business side of things, not with the system itself. It might also require that someone does
     * something manually before allowing the business process to continue, so consider also adding {@link
     * #MANUAL_INTERVENTION_REQUIRED} if this is the case.
     */
    PROCESS_ERROR,

    /**
     * This is primarily intended to be used by the startup and readiness probes, such as those in Kubernetes, and for
     * Health probes in gateway systems.
     * <p>
     * This status indicates that the service is not ready for action yet. The main purpose of this is to handle slow
     * startup, so services have the required time to load caches and prepare whatever they need to in order to be
     * productive members of the society (of microservices).
     * <p>
     * In order to support rolling deploys any deploy scripts should wait for services to be ready before taking down
     * and redeploying the next instance of the service, so we can make sure that there is always at least one instance
     * that is ready during deploys.
     * <p>
     * Note: After startup has finished a service should not normally report "Not ready", unless you want it taken out
     * of the load balancer for any reason. If you do this be aware that Kubernetes and other application gateways will
     * not route HTTP traffic to this instance. Main takeaway: This should primarily only be used during startup.
     * <p>
     * Also note: Make sure that health checks that use this axis does not accidentally throw unhandled exceptions out
     * of the checks, as that will trigger all specified axis, including {@link Axis#NOT_READY}. This may cause the
     * service to be taken out of load balancers and such. You should never let health checks throw unhandled
     * exceptions.
     */
    NOT_READY,

    /**
     * This is primarily intended to be used by the Kubernetes liveness probe, or any other system that has the
     * authority to kill an instance and restart it.
     * <p>
     * This status indicates that something has gone horribly wrong, and the service is in an unrecoverable state.
     * Adding this axis to a health check is simply a cry for help: Please restart me!
     * <p>
     * This allows auto restarts of services in Kubernetes, when used together with liveness probes. Pods that report
     * that they are not live anymore will be killed and restarted.
     * <p>
     * <b>Use with extreme care!</b> Adding this to a health check basically means you allow this service instance
     * to be taken down and restarted without any further notice. Only use this as a last measure if a service goes into
     * an unrecoverable state.
     * <p>
     * <b>Also note:</b> Make sure that health checks that use this axis does not accidentally throw unhandled
     * exceptions out of the checks, as that will trigger all specified axis, including {@link Axis#REQUIRES_REBOOT}.
     * This may cause the service to be rebooted. You should never let health checks throw unhandled exceptions.
     */
    REQUIRES_REBOOT,


    // ==== SYSTEM AXES ================================================================================================
    // These axes are not supposed to be used directly by users, but will be added when relevant. Consumers should
    // always be prepared to handle these axes if they occur. They do not need to be specified.

    /**
     * This indicates that the health check has crashed during execution. Usually this will happen if we catch an
     * unhandled exception. If this axis is triggered you must assume that the health check did not manage to actually
     * check what it was supposed to check. This is likely a bug, and must be fixed in code.
     * <p>
     * This axis correspons to {@link com.storebrand.healthcheck.HealthCheckReportDto.RunStatusDto#crashed}.
     */
    SYS_CRASHED,

    /**
     * This axis indicates that the health check used more than the expected amount of time to execute. This should be
     * looked into. If a check regularly takes more time than expected you should either fix the check, or increase the
     * maximum expected runtime of the health check.
     * <p>
     * This axis correspons to {@link com.storebrand.healthcheck.HealthCheckReportDto.RunStatusDto#slow}.
     */
    SYS_SLOW,

    /**
     * This axis indicates that the status in the health check is stale. It has not been updated within a reasonable
     * time since the last time it was updated. A stale check might indicate that the background thread for the health
     * check might be stuck, or something else might prevent the check from being updated. It should be investigated.
     * <p>
     * This axis correspons to {@link com.storebrand.healthcheck.HealthCheckReportDto.RunStatusDto#stale}.
     */
    SYS_STALE;

    /**
     * Convenience method to create an array of Axis'es - simply to avoid the somewhat verbose <code>new Axis[] {axis1,
     * axis2}</code> code in the {@link CheckSpecification#check(CharSequence, Axis[], Function)
     * CheckSpecification.check(responsible, {axes}, lambda)} invocation, instead being able to use the slightly
     * prettier <code>Axis.of(axis1, axis2)</code>.
     *
     * @param axes
     *         the axes to include
     * @return the array of axes
     */
    public static Axis[] of(Axis... axes) { // NOPMD
        return axes;
    }
}
