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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.storebrand.healthcheck.Axis;
import com.storebrand.healthcheck.CheckSpecification;
import com.storebrand.healthcheck.Responsible;
import com.storebrand.healthcheck.impl.Status.StatusWithAxes;


/**
 * Tests for {@link StatusWithAxes}.
 */
public class StatusWithAxesTests {

    @Test
    public void testEqualStatusWhenBothOk() {
        StatusWithAxes status1 = Status.withAxes(Responsible.DEVELOPERS, "Description",
                Arrays.asList(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.NOT_READY));
        StatusWithAxes status2 = Status.withAxes(Responsible.DEVELOPERS, "Description changed",
                Arrays.asList(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.NOT_READY));

        assertTrue(status1.isEqualStatus(status2));
        assertTrue(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualStatusWhenAxesAreDifferent() {
        StatusWithAxes status1 = Status.withAxes(Responsible.DEVELOPERS, "Description",
                Arrays.asList(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.NOT_READY));
        StatusWithAxes status2 = Status.withAxes(Responsible.DEVELOPERS, "Description",
                Arrays.asList(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.CRITICAL_WAKE_PEOPLE_UP));

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualStatusWhenOneIsOkAndTheOtherIsNot() {
        StatusWithAxes statusOk = Status.withAxes(Responsible.DEVELOPERS, "Description",
                Collections.singletonList(Axis.NOT_READY));
        StatusWithAxes statusNotOk = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);

        assertFalse(statusOk.isEqualStatus(statusNotOk));
        assertFalse(statusNotOk.isEqualStatus(statusOk));
    }

    @Test
    public void testEqualStatusWhenBothNotOk() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);

        assertTrue(status1.isEqualStatus(status2));
        assertTrue(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualStatusWhenAxesChanged() {
        StatusWithAxes status1 = Status.withAxes(Responsible.DEVELOPERS, "Description",
                Arrays.asList(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.NOT_READY))
                .setAllAxes(true);
        StatusWithAxes status2 = Status.withAxes(Responsible.DEVELOPERS, "Description",
                Arrays.asList(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.NOT_READY))
                .setAxes(true, Axis.MANUAL_INTERVENTION_REQUIRED);

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualStatusWhenEntitiesChanged() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        status1.setAffectedEntities(CheckSpecification.EntityRef.of("order", "1", "2", "3"));
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        status2.setAffectedEntities(CheckSpecification.EntityRef.of("order", "2", "1"));

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

    @Test
    public void testEqualStatusWhenEntitiesTheSame() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        status1.setAffectedEntities(CheckSpecification.EntityRef.of("order", "1", "2", "3"));
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description changed",
                Axis.NOT_READY);
        status2.setAffectedEntities(CheckSpecification.EntityRef.of("order", "2", "1", "3"));

        assertTrue(status1.isEqualStatus(status2));
        assertTrue(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualStatusWhenOnlyOneHaveAffectedEntities() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        status1.setAffectedEntities(CheckSpecification.EntityRef.of("order", "1", "2", "3"));
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualStatusWhenStaticCompareStringIsDifferent() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        status1.setStaticCompareString("compare1");
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        status2.setStaticCompareString("compare2");

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

    @Test
    public void testEqualStatusWhenStaticCompareStringIsEqual() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        status1.setStaticCompareString("compare1");
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description changed",
                Axis.NOT_READY);
        status2.setStaticCompareString("compare1");

        assertTrue(status1.isEqualStatus(status2));
        assertTrue(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualWhenOnlyOneHaveStaticCompareString() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        status1.setStaticCompareString("compare1");
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualWhenDescriptionChangedWhenNoAffectedEntitiesOrStaticCompareString() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description changed",
                Axis.NOT_READY);

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

    @Test
    public void testEqualWhenNotOk() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);

        assertTrue(status1.isEqualStatus(status2));
        assertTrue(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualWhenResponsibleChange() {
        StatusWithAxes status1 = Status.withAxes(Responsible.DEVELOPERS, "Description",
                Arrays.asList(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.NOT_READY));
        StatusWithAxes status2 = Status.withAxes(Responsible.OPERATIONS, "Description",
                Arrays.asList(Axis.MANUAL_INTERVENTION_REQUIRED, Axis.NOT_READY));

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

    @Test
    public void testNotEqualWhenNotOkAndResponsibleChange() {
        StatusWithAxes status1 = Status.withOneActiveAxis(Responsible.DEVELOPERS, "Description",
                Axis.NOT_READY);
        StatusWithAxes status2 = Status.withOneActiveAxis(Responsible.OPERATIONS, "Description",
                Axis.NOT_READY);

        assertFalse(status1.isEqualStatus(status2));
        assertFalse(status2.isEqualStatus(status1));
    }

}
