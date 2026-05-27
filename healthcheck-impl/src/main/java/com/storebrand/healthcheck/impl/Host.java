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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains methods {@link #getLocalHostName()} that hopefully returns the current/running/local host name, and
 * {@link #getLocalHostAddress()} which returns the current/running/local host IP-address.
 *
 * @author Endre Stølsvik, 2013 - http://endre.stolsvik.com/
 */
final class Host {
    private static final Logger log = LoggerFactory.getLogger(Host.class);

    private Host() {
        /* hide */
    }

    private static final String __localhostName = tryToFindLocalHostname();

    private static final long __totalPhysicalMemory = tryToFindTotalPhysicalMemory();

    private static final Map<NetworkInterface, List<InetAddress>> __networkInterfaceInetAddressMap =
            tryToFindNetworkInterfaceAddressMap();

    private static final String __localhostAddress = tryToFindLocalhostAddress();

    /**
     * Gets the hostname by command 'hostname'. Caches, so it will only do this once ever for the JVM.
     *
     * @return the hostname of the current/running/local host. Hopefully.
     */
    static String getLocalHostName() {
        return __localhostName;
    }

    /**
     * @return total amount of physical memory in bytes.
     */
    static long getTotalPhysicalMemory() {
        return __totalPhysicalMemory;
    }

    /**
     * Get all NetworkInterface on this host with a list of the InetAddress (IPv4 and IPv6 addresses).
     *
     * @return all NetworkInterface on this host with a list of the InetAddress (IPv4 and IPv6 addresses).
     * @implNote This call may take a bit of time, and may be called many times, so it is cached for the lifetime of
     *           the JVM.
     */
    static Map<NetworkInterface, List<InetAddress>> getLocalHostAddresses() {
        return __networkInterfaceInetAddressMap;
    }

    /**
     * Get the addresses for the current host. Excluding link local and loop back addresses that might be present on
     * Linux machines (e.g. Ubuntu have a loop back interface with address 127.0.1.1).
     *
     * @return the IPv4 address of the current/running/local host - excludes link local and loop back addresses.</code>.
     * @implNote This call may take a bit of time, and may be called many times, so it is cached for the lifetime of
     *           the JVM.
     */
    static String getLocalHostAddress() {
        return __localhostAddress;
    }

    /**
     * Finds the total amount of physical memory through an attribute on the mbeanserver.
     */
    private static long tryToFindTotalPhysicalMemory() {
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        try {
            String totalMemory = mBeanServer.getAttribute(new ObjectName("java.lang", "type", "OperatingSystem"),
                    "TotalPhysicalMemorySize").toString();
            return Long.parseLong(totalMemory);
        }
        catch (AttributeNotFoundException | InstanceNotFoundException | MalformedObjectNameException | MBeanException
                | ReflectionException e) {
            throw new AssertionError("Finding total physical memory SHALL work out, or else system shall not start.",
                    e);
        }
    }

    @SuppressWarnings({"PMD.AssignmentInOperand", "PMD.AvoidCatchingThrowable"})
    private static String tryToFindLocalHostname() {
        String hostname = null;

        // :: Try to determine hostname by using the 'hostname' command
        try {
            Process proc = Runtime.getRuntime().exec("hostname");
            try (InputStreamReader in = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8);
                 BufferedReader br = new BufferedReader(in)) {
                // Read the first line trimmed, which will be the hostname as returned by the executable. We would not
                // be able to use a multiline hostname.
                hostname = br.readLine();
            }
        }
        // CHECKSTYLE IGNORE check FOR NEXT 1 LINES: "Catching Throwable is not allowed" - but I am the boss. -est
        catch (Throwable t) {
            // There is no need to log the actual throwable here. This will always fail in Kubernetes if the hostname
            // executable is not available, and putting "Cannot run program 'hostname', No such file or directory" in
            // the logs gives no additional value.
            log.info("Could not determine hostname by using 'hostname' command."
                    + " Will use InetAddress.getLocalHost().getHostName() instead.");
        }

        // :? Did the hostname command return a blankk/null hostname?
        if (hostname == null || "".equals(hostname.trim())) {
            // Yes -> then we use the alternative InetAddress-method for determining hostname
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            }
            catch (UnknownHostException e) {
                log.error("Failed to determine hostname by using InetAddress.getLocalHost().getHostName(): "
                        + e.getMessage(), e);
                hostname = "_cannot_find_hostname_";
            }
        }

        // If the hostname contains FQDN we strip away the domain part, leaving only what we assume are the hostname
        int dot = hostname.indexOf('.');
        if (dot > 0) {
            hostname = hostname.substring(0, dot);
        }

        // Make sure to trim out any leading and trailing whitespace
        return hostname.trim();
    }

    /** Initialize map of network interface to inetadresses. */
    private static <T> Map<NetworkInterface, List<InetAddress>> tryToFindNetworkInterfaceAddressMap() {
        try {
            Map<NetworkInterface, List<InetAddress>> map = new HashMap<>();
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                map.put(n, Collections.list(n.getInetAddresses()));
            }
            // Set the map that is returned to the clients to be unmodifiable, to avoid clients polluting the map.
            return Collections.unmodifiableMap(map);
        }
        catch (SocketException e) {
            throw new AssertionError("The NetworkInterface.getNetworkInterfaces() SHALL work out,"
                    + " or else system shall not start.", e);
        }
    }

    /** Initialize localhost address. */
    private static String tryToFindLocalhostAddress() {
        return getLocalHostAddresses().entrySet().stream()
                .filter(netInterface -> {
                    try {
                        return netInterface.getKey().isUp();
                    }
                    catch (SocketException e) {
                        log.warn("Unable to check if NetworkInterface is up."
                                + " Interface [" + netInterface.getKey().getDisplayName() + "].", e);
                        return false;
                    }
                })
                .map(Map.Entry::getValue)
                .flatMap(Collection::stream)
                .filter(inetAddress -> inetAddress instanceof Inet4Address)
                .filter(inetAddress -> !inetAddress.isLoopbackAddress())
                .filter(inetAddress -> !inetAddress.isAnyLocalAddress())
                .map(InetAddress::getHostAddress)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Can't find IPv4 address after filtering"
                        + " out link local and loop back addresses."));
    }
}
