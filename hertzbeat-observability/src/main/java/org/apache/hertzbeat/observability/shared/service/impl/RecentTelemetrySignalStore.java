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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Bounded in-memory storage for the most recently ingested telemetry signals.
 */
@Component
public class RecentTelemetrySignalStore {

    private static final int MAX_RECENT_SIGNALS = 256;

    private final ConcurrentLinkedDeque<RecentMetricSignal> recentMetrics = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<RecentLogSignal> recentLogs = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<RecentTraceSignal> recentTraces = new ConcurrentLinkedDeque<>();

    void recordMetric(Map<String, String> canonicalIdentities,
                      Long observedAt,
                      String metricName,
                      String metricType,
                      String unit,
                      Double value,
                      Map<String, String> attributes) {
        recentMetrics.addFirst(new RecentMetricSignal(
                snapshot(canonicalIdentities),
                observedAt,
                metricName,
                metricType,
                unit,
                value,
                snapshot(attributes)
        ));
        trim(recentMetrics);
    }

    void recordLog(Map<String, String> canonicalIdentities,
                   Long observedAt,
                   String body,
                   String severityText,
                   String traceId,
                   String spanId,
                   Map<String, String> resource,
                   Map<String, String> attributes) {
        recentLogs.addFirst(new RecentLogSignal(
                snapshot(canonicalIdentities),
                observedAt,
                body,
                severityText,
                traceId,
                spanId,
                snapshot(resource),
                snapshot(attributes)
        ));
        trim(recentLogs);
    }

    void recordTrace(Map<String, String> canonicalIdentities,
                     Long observedAt,
                     String traceId,
                     String spanId,
                     String spanName,
                     String serviceName,
                     String serviceNamespace,
                     String errorState,
                     Map<String, String> resource,
                     Map<String, String> spanAttributes) {
        recentTraces.addFirst(new RecentTraceSignal(
                snapshot(canonicalIdentities),
                observedAt,
                traceId,
                spanId,
                spanName,
                serviceName,
                serviceNamespace,
                errorState,
                snapshot(resource),
                snapshot(spanAttributes)
        ));
        trim(recentTraces);
    }

    List<RecentMetricSignal> recentMetrics() {
        return List.copyOf(recentMetrics);
    }

    List<RecentLogSignal> recentLogs() {
        return List.copyOf(recentLogs);
    }

    List<RecentTraceSignal> recentTraces() {
        return List.copyOf(recentTraces);
    }

    private <T> void trim(ConcurrentLinkedDeque<T> signals) {
        while (signals.size() > MAX_RECENT_SIGNALS) {
            signals.pollLast();
        }
    }

    private Map<String, String> snapshot(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    record RecentMetricSignal(Map<String, String> canonicalIdentities,
                              Long observedAt,
                              String metricName,
                              String metricType,
                              String unit,
                              Double value,
                              Map<String, String> attributes) {
    }

    record RecentLogSignal(Map<String, String> canonicalIdentities,
                           Long observedAt,
                           String body,
                           String severityText,
                           String traceId,
                           String spanId,
                           Map<String, String> resource,
                           Map<String, String> attributes) {
    }

    record RecentTraceSignal(Map<String, String> canonicalIdentities,
                             Long observedAt,
                             String traceId,
                             String spanId,
                             String spanName,
                             String serviceName,
                             String serviceNamespace,
                             String errorState,
                             Map<String, String> resource,
                             Map<String, String> spanAttributes) {
    }
}
