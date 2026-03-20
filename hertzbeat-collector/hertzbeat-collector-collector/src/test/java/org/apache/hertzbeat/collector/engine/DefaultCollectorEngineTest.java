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

import org.apache.hertzbeat.collector.dispatch.entrance.internal.CollectJobService;
import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test case for {@link DefaultCollectorEngine}
 */
@ExtendWith(MockitoExtension.class)
class DefaultCollectorEngineTest {

    @Mock
    private CollectJobService collectJobService;

    @InjectMocks
    private DefaultCollectorEngine defaultCollectorEngine;

    private Job job;

    @BeforeEach
    void setUp() {
        job = new Job();
        job.setId(1L);
    }

    @Test
    void testSubmitAsync() {
        defaultCollectorEngine.submitAsync(job);
        verify(collectJobService).addAsyncCollectJob(job);
    }

    @Test
    void testCollectOnce() {
        List<CollectRep.MetricsData> expected = List.of(CollectRep.MetricsData.newBuilder().build());
        when(collectJobService.collectSyncJobData(job)).thenReturn(expected);

        List<CollectRep.MetricsData> result = defaultCollectorEngine.collectOnce(job);

        assertEquals(expected, result);
        verify(collectJobService).collectSyncJobData(job);
    }

    @Test
    void testCancel() {
        defaultCollectorEngine.cancel(1L);
        verify(collectJobService).cancelAsyncCollectJob(1L);
    }

    @Test
    void testShutdown() {
        assertDoesNotThrow(() -> defaultCollectorEngine.shutdown());
    }
}
