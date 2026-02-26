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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.collector.dispatch.entrance.internal.CollectJobService;
import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring-managed implementation of {@link CollectorEngine} that delegates to the
 * existing {@link CollectJobService}.
 *
 * <p>This class is the <em>embedded runtime adapter</em>: the manager (and any other
 * Spring context that has the collector on its classpath) can simply inject
 * {@code CollectorEngine} and remain unaware of the underlying scheduling
 * infrastructure.
 *
 * <p>When a Quarkus-based standalone runtime is introduced in a later phase, it will
 * provide its own {@link CollectorEngine} implementation without touching this class
 * or the callers.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultCollectorEngine implements CollectorEngine {

    private final CollectJobService collectJobService;

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CollectJobService#addAsyncCollectJob(Job)}.
     * The job ID must have been set by the caller prior to this invocation.
     */
    @Override
    public void submitAsync(Job job) {
        collectJobService.addAsyncCollectJob(job);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CollectJobService#collectSyncJobData(Job)} and blocks
     * until all metrics have been collected or the internal timeout fires.
     */
    @Override
    public List<CollectRep.MetricsData> collectOnce(Job job) {
        return collectJobService.collectSyncJobData(job);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CollectJobService#cancelAsyncCollectJob(Long)}.
     */
    @Override
    public void cancel(long jobId) {
        collectJobService.cancelAsyncCollectJob(jobId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The Spring lifecycle manages shutdown for this embedded implementation,
     * so no explicit action is taken here.
     */
    @Override
    public void shutdown() {
        log.info("DefaultCollectorEngine shutdown – Spring lifecycle handles teardown.");
    }
}
