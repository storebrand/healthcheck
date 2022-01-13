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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Combines one or more instance resolvers, in order to create a prioritized chain of resolvers. When asking for an
 * instance this will go through all instance resolvers in order until it gets any instances. If no instances are found
 * it will throw.
 * <p>
 * This can typically be used to combine an instance resolver that fetches Spring beans, and the {@link
 * SimpleInstanceResolver}. In this scenario we first ask the Spring resolver if it has any beans, and if not we try to
 * fetch the instance with the {@link SimpleInstanceResolver}.
 *
 * @author Kristian Hiim, 2021-2022 kristian@hiim.no
 * @author Endre Stølsvik, 2021-2022 endre@stolsvik.com
 * @author Hallvard Nygård, Knut Saua Mathiesen, Endre Stølsvik, Dag Lennart Bertelsen, Kevin Mc Tiernan 2014-2021: former ServerStatus-solution and discussions/input
 */
public class CombinedInstanceResolver implements HealthCheckInstanceResolver {

    private final List<HealthCheckInstanceResolver> _resolvers;

    public CombinedInstanceResolver(List<HealthCheckInstanceResolver> resolvers) {
        _resolvers = resolvers;
    }

    @Override
    public <T> Collection<T> getInstancesFor(Class<T> clazz) {
        for (HealthCheckInstanceResolver resolver : _resolvers) {
            Collection<T> instances = resolver.getInstancesFor(clazz);
            if (!instances.isEmpty()) {
                return instances;
            }
        }
        throw new IllegalStateException("None of the registered resolvers could create an instance of the class "
                + "[" + clazz.getName() + "]");
    }

    /**
     * Convenience factory method for creating an {@link CombinedInstanceResolver} that combines one or more {@link
     * HealthCheckInstanceResolver}s.
     */
    public static CombinedInstanceResolver of(HealthCheckInstanceResolver... instanceResolvers) { // NOPMD
        return new CombinedInstanceResolver(Arrays.asList(instanceResolvers));
    }
}
