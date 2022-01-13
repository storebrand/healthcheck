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

package com.storebrand.serial;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import org.junit.Test;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.HealthCheckReportDto;
import com.storebrand.healthcheck.HealthCheckReportDto.AxesDto;
import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;
import com.storebrand.healthcheck.HealthCheckReportDto.RunStatusDto;
import com.storebrand.healthcheck.HealthCheckReportDto.StatusDto;
import com.storebrand.healthcheck.HealthCheckReportDto.ThrowableHolderDto;
import com.storebrand.healthcheck.ServiceInfo;
import com.storebrand.healthcheck.serial.HealthCheckReportJsonSerializer;

public class HealthCheckReportSerializerTest {

    @Test
    public void testSerializingAndDezerialising() {
        // :: Arrange a report
        HealthCheckReportDto dto = new HealthCheckReportDto();
        dto.healthChecks = new ArrayList<>();

        // Adding service info
        ServiceInfo serviceInfo = new ServiceInfo("Project-T", "Version-T");
        dto.service = serviceInfo.getServiceInfo();

        // Adding HealthCheckDto
        HealthCheckDto healthCheckDto = new HealthCheckDto();
        healthCheckDto.runStatus = new RunStatusDto();
        healthCheckDto.statuses = new ArrayList<>();
        healthCheckDto.name = "Test check";
        healthCheckDto.axes = new AxesDto();
        healthCheckDto.axes.activated = Collections.singleton(Axis.DEGRADED_MINOR);
        healthCheckDto.axes.specified = Collections.singleton(Axis.DEGRADED_MINOR);
        dto.healthChecks.add(healthCheckDto);

        // Adding StatusDto
        StatusDto statusDto = new StatusDto();
        statusDto.description = "This is a description";
        AxesDto axesDto = new AxesDto();
        axesDto.activated = Collections.singleton(Axis.DEGRADED_MINOR);
        axesDto.specified = Collections.singleton(Axis.DEGRADED_MINOR);
        statusDto.axes = Optional.of(axesDto);
        statusDto.exception = Optional.of(new ThrowableHolderDto(new RuntimeException("testing")));
        healthCheckDto.statuses.add(statusDto);

        // :: Act serializing and deserializing
        HealthCheckReportJsonSerializer serializer = new HealthCheckReportJsonSerializer();
        String serialized = serializer.serialize(dto);
        HealthCheckReportDto result = serializer.deserialize(serialized);

        // :: Assert
        assertEquals("Project-T", result.service.project.name);
        assertEquals("Version-T", result.service.project.version);

        // Verify that we have added DTOs all the way down to the ThrowableHolderDto, and that the message matches.
        // This should be enough to verify that we have serialized and deserialized the same data.
        assertEquals("testing", result.healthChecks.get(0).statuses.get(0).exception.get().message);
    }
}
