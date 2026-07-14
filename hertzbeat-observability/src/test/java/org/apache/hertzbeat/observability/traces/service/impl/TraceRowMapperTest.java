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

package org.apache.hertzbeat.observability.traces.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hertzbeat.common.observability.dto.trace.TraceListItemDto;
import org.apache.hertzbeat.common.observability.dto.trace.TraceSpanNodeDto;
import org.junit.jupiter.api.Test;

class TraceRowMapperTest {

    private final TraceRowMapper mapper = new TraceRowMapper();

    @Test
    void mapsSpanAttributesEventsLinksAndBoundedNumbers() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trace_id", "trace-1");
        row.put("span_id", "span-1");
        row.put("span_name", "POST /checkout");
        row.put("span_status_code", "STATUS_CODE_ERROR");
        row.put("duration_nano", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        row.put("timestamp", "2026-07-14T10:15:30Z");
        row.put("resource_attributes", List.of(
                Map.of("key", "service.name", "value", Map.of("stringValue", "checkout")),
                Map.of("key", "service.namespace", "value", Map.of("stringValue", "shop"))
        ));
        row.put("span_attributes", Map.of("http.route", "/checkout"));
        row.put("span_events", """
                [{"timeUnixNano":42,"name":"retry","attributes":{"retry.count":1}}]
                """);
        row.put("span_links", """
                [{"traceId":"trace-upstream","spanId":"span-upstream","attributes":{"kind":"follows"}}]
                """);

        TraceSpanNodeDto span = mapper.toSpanNode(row);

        assertEquals("checkout", span.getServiceName());
        assertEquals(Long.MAX_VALUE, span.getDurationNanos());
        assertEquals(Instant.parse("2026-07-14T10:15:30Z").toEpochMilli(), span.getStartTime());
        assertEquals("/checkout", span.getSpanAttributes().get("http.route"));
        assertEquals("retry", span.getEvents().getFirst().getName());
        assertEquals("trace-upstream", span.getLinks().getFirst().getTraceId());
        assertTrue(span.isHighlighted());
    }

    @Test
    void mapsAggregateRowsWithResourceFallbacks() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trace_id", "trace-2");
        row.put("root_span_id", "span-root");
        row.put("root_span_name", "GET /inventory");
        row.put("status", "error");
        row.put("timestamp", 1_720_000_000_000L);
        row.put("duration_nano", -1L);
        row.put("resource_attributes", Map.of(
                "service.name", "inventory",
                "service.namespace", "shop"
        ));

        TraceListItemDto item = mapper.toTraceListItem(row);

        assertEquals("inventory", item.getServiceName());
        assertEquals("shop", item.getServiceNamespace());
        assertEquals(0L, item.getDurationNanos());
        assertEquals(1, item.getErrorSpanCount());
    }
}
