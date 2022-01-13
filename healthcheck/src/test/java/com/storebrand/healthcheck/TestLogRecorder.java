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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.storebrand.healthcheck.HealthCheckReportDto.HealthCheckDto;

public class TestLogRecorder implements HealthCheckLogger {
    private final List<HealthCheckDto> _loggedHealthCheckDtos = new ArrayList<>();

    @Override
    public void logHealthCheckResult(HealthCheckDto dto) {
        _loggedHealthCheckDtos.add(dto);
    }

    public List<HealthCheckDto> getLoggedHealthCheckDtos() {
        return _loggedHealthCheckDtos;
    }

    public List<HealthCheckDto> getLoggedAt(Instant time) {
        return _loggedHealthCheckDtos.stream()
                .filter(dto -> dto.runStatus.checkStarted.equals(time))
                .collect(toList());
    }
}
