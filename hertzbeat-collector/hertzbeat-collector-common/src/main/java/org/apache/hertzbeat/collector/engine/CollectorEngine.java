/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.collector.engine;

import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.message.CollectRep;

import java.util.List;

/**
 * Stable API for the collector engine.
 *
 * <p>This interface decouples the collection logic from the runtime environment so that
 * the same core can be used in:
 * <ul>
 *   <li>Embedded mode – the manager starts a local engine (MAIN_COLLECTOR_NODE)</li>
 *   <li>Standalone mode – a dedicated collector process (e.g. Quarkus runtime) connects
 *       to the manager via Netty</li>
 * </ul>
 *
 * <p>Callers should depend on this interface rather than on concrete implementations or
 * Spring beans so that the runtime can be swapped without touching business logic.
 */
public interface CollectorEngine {

    /**
     * Submit a cyclic (async) collect job.
     * The caller is responsible for setting a unique {@link Job#getId()} before
     * passing the job in.
     *
     * @param job collect job – must have a valid ID assigned
     */
    void submitAsync(Job job);

    /**
     * Execute a one-time, synchronous collection and block until all metrics
     * have been gathered or the timeout expires.
     *
     * @param job collect job
     * @return list of collected metrics data; never {@code null}
     */
    List<CollectRep.MetricsData> collectOnce(Job job);

    /**
     * Cancel a previously submitted cyclic job.
     *
     * @param jobId ID of the job to cancel
     */
    void cancel(long jobId);

    /**
     * Gracefully shut down the engine and release all resources.
     */
    void shutdown();
}
