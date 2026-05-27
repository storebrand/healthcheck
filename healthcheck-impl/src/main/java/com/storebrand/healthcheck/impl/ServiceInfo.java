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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;

import com.storebrand.healthcheck.HealthCheckRegistry.InfoPropertiesSupplier;
import com.storebrand.healthcheck.HealthCheckRegistry.InfoProperty;
import com.storebrand.healthcheck.HealthCheckReportDto;
import com.storebrand.healthcheck.HealthCheckReportDto.ServiceInfoDto;
import com.storebrand.healthcheck.HealthCheckReportDto.ServiceInfoDto.HostDto;
import com.storebrand.healthcheck.HealthCheckReportDto.ServiceInfoDto.LoadDto;
import com.storebrand.healthcheck.HealthCheckReportDto.ServiceInfoDto.MemoryDto;
import com.storebrand.healthcheck.HealthCheckReportDto.ServiceInfoDto.ProjectDto;
import com.storebrand.healthcheck.HealthCheckReportDto.ServiceInfoDto.PropertyDto;
import com.sun.management.OperatingSystemMXBean;

/**
 * Service that retrieves different statuses for a service like load, available cpu's, IP, hostname and Java version.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class ServiceInfo {

    private final String _projectName;
    private final String _projectVersion;
    private final CopyOnWriteArrayList<InfoPropertiesSupplier> _additionalPropertiesSuppliers = new CopyOnWriteArrayList<>();

    public ServiceInfo(String projectName, String projectVersion) {
        this(projectName, projectVersion, null);
    }

    public ServiceInfo(String projectName, String projectVersion, InfoPropertiesSupplier additionalPropertiesSupplier) {
        _projectName = projectName;
        _projectVersion = projectVersion;
        if (additionalPropertiesSupplier != null) {
            _additionalPropertiesSuppliers.add(additionalPropertiesSupplier);
        }
    }

    /**
     * Get runtime info for the service as runningSince, timeNow, Memory, cpu, host, and java versions. This is part of
     * the {@link HealthCheckReportDto}, but can also be used directly by anyone that needs this information.
     */
    public ServiceInfoDto getServiceInfo() {
        Runtime runtime = Runtime.getRuntime();

        HostDto hostDto = new HostDto();
        hostDto.name = Host.getLocalHostName();
        hostDto.primaryAddress = Host.getLocalHostAddress();

        ProjectDto projectDto = new ProjectDto();
        projectDto.name = _projectName;
        projectDto.version = _projectVersion;

        MemoryDto memoryDto = new MemoryDto();
        memoryDto.systemTotal = Host.getTotalPhysicalMemory();

        memoryDto.heapMaxAllowed = OptionalLong.of(runtime.maxMemory());
        memoryDto.heapAllocated = runtime.totalMemory();
        memoryDto.heapUsed = memoryDto.heapAllocated - runtime.freeMemory();

        LoadDto loadDto = new LoadDto();
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean) {
            OperatingSystemMXBean osmxbean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

            memoryDto.systemFree = OptionalLong.of(osmxbean.getFreePhysicalMemorySize());

            double system = osmxbean.getSystemCpuLoad();
            double process = osmxbean.getProcessCpuLoad();
            // CPU Load is a double in range [0.0, 1.0].
            // If the value is negative not available, and we default to null instead.
            // As NaN is not a valid JSON we also set that to null.
            loadDto.system = (system < 0) || Double.isNaN(system) ? OptionalDouble.empty() : OptionalDouble.of(system);
            loadDto.process = (process < 0) || Double.isNaN(process) ? OptionalDouble.empty() : OptionalDouble.of(process);
        }

        HealthCheckReportDto.ServiceInfoDto dto = new HealthCheckReportDto.ServiceInfoDto();
        dto.host = hostDto;
        dto.project = projectDto;
        dto.cpus = Runtime.getRuntime().availableProcessors();
        dto.operatingSystem = System.getProperty("os.name");
        dto.runningUser = System.getProperty("user.name");
        dto.memory = memoryDto;
        dto.load = loadDto;
        dto.runningSince = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        dto.timeNow = Instant.now();

        dto.properties = new ArrayList<>();
        dto.properties.add(createPropertyDto("java.version", "Java version", System.getProperty("java.version")));
        dto.properties.add(createPropertyDto("jvm.version", "JVM version",
                System.getProperty("java.vm.vendor")
                        + " / " + System.getProperty("java.vm.name")
                        + " / " + System.getProperty("java.vm.version")));

        // Add any additional properties
        for (InfoPropertiesSupplier supplier : _additionalPropertiesSuppliers) {
            supplier.getAdditionalProperties().stream()
                    .map(ServiceInfo::toPropertyDto)
                    .forEach(dto.properties::add);
        }

        return dto;
    }

    void addAdditionalPropertiesSupplier(InfoPropertiesSupplier supplier) {
        _additionalPropertiesSuppliers.add(supplier);
    }

    private static PropertyDto createPropertyDto(String name, String displayName, String value) {
        PropertyDto dto = new PropertyDto();
        dto.name = name;
        dto.displayName = Optional.ofNullable(displayName);
        dto.value = value;
        return dto;
    }

    private static PropertyDto toPropertyDto(InfoProperty property) {
        PropertyDto propertyDto = new PropertyDto();
        propertyDto.name = property.getName();
        propertyDto.displayName = property.getDisplayName();
        propertyDto.value = property.getValue();
        return propertyDto;
    }
}
