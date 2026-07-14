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

package org.apache.hertzbeat.observability.shared.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecentTelemetrySignalStoreTest {

    @Test
    void storesDefensiveSnapshotsWithNewestSignalFirst() {
        RecentTelemetrySignalStore store = new RecentTelemetrySignalStore();
        Map<String, String> identities = new LinkedHashMap<>(Map.of("service.name", "checkout"));
        Map<String, String> attributes = new LinkedHashMap<>(Map.of("http.method", "POST"));

        store.recordMetric(identities, 10L, "request_count", "sum", "1", 3.0, attributes);
        identities.put("service.name", "mutated");
        attributes.put("http.method", "GET");
        store.recordMetric(Map.of("service.name", "payments"), 20L,
                "error_count", "sum", "1", 1.0, Map.of());

        assertEquals(2, store.recentMetrics().size());
        assertEquals("payments", store.recentMetrics().getFirst().canonicalIdentities().get("service.name"));
        assertEquals("checkout", store.recentMetrics().getLast().canonicalIdentities().get("service.name"));
        assertEquals("POST", store.recentMetrics().getLast().attributes().get("http.method"));
    }

    @Test
    void boundsEachSignalTypeToTheRecentWindow() {
        RecentTelemetrySignalStore store = new RecentTelemetrySignalStore();

        for (int index = 0; index <= 256; index++) {
            Map<String, String> identities = Map.of("service.name", "service-" + index);
            store.recordMetric(identities, (long) index, "metric-" + index, "gauge", "1",
                    (double) index, Map.of());
            store.recordLog(identities, (long) index, "body-" + index, "INFO",
                    "trace-" + index, "span-" + index, Map.of(), Map.of());
            store.recordTrace(identities, (long) index, "trace-" + index, "span-" + index,
                    "operation-" + index, "service-" + index, null, null, Map.of(), Map.of());
        }

        assertEquals(256, store.recentMetrics().size());
        assertEquals(256, store.recentLogs().size());
        assertEquals(256, store.recentTraces().size());
        assertEquals(256L, store.recentMetrics().getFirst().observedAt());
        assertEquals(1L, store.recentMetrics().getLast().observedAt());
        assertEquals(256L, store.recentLogs().getFirst().observedAt());
        assertEquals(1L, store.recentLogs().getLast().observedAt());
        assertEquals(256L, store.recentTraces().getFirst().observedAt());
        assertEquals(1L, store.recentTraces().getLast().observedAt());
    }
}
