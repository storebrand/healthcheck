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
 * Defines the "team" that should be the first to inspect a status check that fails. Note that this enum implements
 * {@link CharSequence}, and it is possible to define your own teams. The API accepts any {@link CharSequence} as
 * responsible.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public enum Responsible implements CharSequence {
    /**
     * Probably a code problem, or corner case, which developers needs to look into.
     */
    DEVELOPERS,

    /**
     * Probably some server that is down, slow network, or similar, which operations must look into.
     */
    OPERATIONS,

    /**
     * "Inner workings" of a company/department, e.g. problem with settling of orders or similar.
     * <p/>
     * https://en.wikipedia.org/wiki/Back_office
     */
    BACK_OFFICE,

    /**
     * "Customer facing" of a company/department, e.g. some customer info is wrongly entered or similar.
     * <p/>
     * https://en.wikipedia.org/wiki/Front_office
     */
    FRONT_OFFICE;

    @Override
    public int length() {
        return name().length();
    }

    @Override
    public char charAt(int index) {
        return name().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return name().subSequence(start, end);
    }

    /**
     * Convenience method to create an array of {@link CharSequence} - simply to avoid the somewhat verbose
     * <code>new CharSequence[] {team1, team2}</code> code in the
     * {@link CheckSpecification#check(CharSequence[], Axis[], Function) CheckSpecification.check({responsible}, {axes},
     * lambda)} invocation, instead being able to use the slightly prettier <code>Responsible.teams(team1, team2)</code>.
     *
     * @param teams
     *         the teams to include
     * @return the array of axes
     */
    public static CharSequence[] teams(CharSequence... teams) {
        return teams;
    }
}
