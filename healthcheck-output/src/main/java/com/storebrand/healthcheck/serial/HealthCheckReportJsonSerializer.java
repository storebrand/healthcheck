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

package com.storebrand.healthcheck.serial;

import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.storebrand.healthcheck.HealthCheckReportDto;

/**
 * Serializer for making a {@link HealthCheckReportDto} into a JSON string, and back again. Handles {@link Instant} and
 * {@link Optional}, so we can have ISO formatted dates, and nullable fields as optionals.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class HealthCheckReportJsonSerializer {

    private final ObjectMapper _objectMapper;
    private final ObjectWriter _objectWriter;

    public HealthCheckReportJsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();

        // Drop nulls
        mapper.setSerializationInclusion(Include.NON_NULL);

        // If props are in JSON that aren't in Java DTO, do not fail.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Write e.g. Dates as "1975-03-11" instead of timestamp, and instead of array-of-ints [1975, 3, 11].
        // Uses ISO8601 with milliseconds and timezone (if present).
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Handle Optional, OptionalLong, OptionalDouble
        mapper.registerModule(new Jdk8Module());

        _objectMapper = mapper;
        _objectWriter = _objectMapper.writerWithDefaultPrettyPrinter();
    }

    public String serialize(HealthCheckReportDto dto) {
        try {
            return _objectWriter.writeValueAsString(dto);
        }
        catch (JsonProcessingException e) {
            throw new SerializationException("Couldn't serialize HealthCheckReportDto.", e);
        }
    }

    public HealthCheckReportDto deserialize(String serialized) {
        try {
            return _objectMapper.readValue(serialized, HealthCheckReportDto.class);
        }
        catch (JsonProcessingException e) {
            throw new SerializationException("Couldn't deserialize HealthCheckReportDto.", e);
        }
    }

    /**
     * The methods in this serializer shall throw this RuntimeException if they encounter problems.
     */
    public static final class SerializationException extends RuntimeException {
        SerializationException(String message) {
            super(message);
        }

        SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
