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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.common.observability.dto.trace.TraceListItemDto;
import org.apache.hertzbeat.common.observability.dto.trace.TraceSpanEventDto;
import org.apache.hertzbeat.common.observability.dto.trace.TraceSpanLinkDto;
import org.apache.hertzbeat.common.observability.dto.trace.TraceSpanNodeDto;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps raw Greptime trace rows into trace query DTOs.
 */
@Component
@Slf4j
final class TraceRowMapper {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final BigInteger LONG_MAX_VALUE = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger LONG_MIN_VALUE = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX_DECIMAL = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal LONG_MIN_DECIMAL = BigDecimal.valueOf(Long.MIN_VALUE);

    TraceListItemDto toTraceListItem(Map<String, Object> row) {
        Map<String, String> resourceAttributes = parseAttributes(
                row.get("resource_attributes"), "resource_attributes.", row);
        String serviceName = defaultText(readText(row, "service_name"), resourceAttributes.get("service.name"));
        String serviceNamespace = defaultText(readText(row, "service_namespace"),
                resourceAttributes.get("service.namespace"));
        String status = normalizeStatus(defaultText(readText(row, "span_status_code"), readText(row, "status")));
        Integer errorSpanCount = readNonNegativeIntValue(row, "error_span_count", "errorSpanCount");
        return new TraceListItemDto(
                readText(row, "trace_id"),
                defaultText(readText(row, "root_span_id"), readText(row, "span_id")),
                serviceName,
                serviceNamespace,
                defaultText(readText(row, "root_span_name"),
                        defaultText(readText(row, "span_name"), readText(row, "name"))),
                readNonNegativeLong(row, "duration_nano"),
                status,
                readTimestamp(row, "timestamp"),
                errorSpanCount == null ? ("error".equals(status) ? 1 : 0) : errorSpanCount,
                resourceAttributes
        );
    }

    TraceSpanNodeDto toSpanNode(Map<String, Object> row) {
        Map<String, String> resourceAttributes = parseAttributes(
                row.get("resource_attributes"), "resource_attributes.", row);
        Map<String, String> spanAttributes = parseAttributes(
                row.get("span_attributes"), "span_attributes.", row);
        String status = normalizeStatus(readText(row, "span_status_code"));
        TraceSpanNodeDto span = new TraceSpanNodeDto();
        span.setTraceId(readText(row, "trace_id"));
        span.setSpanId(readText(row, "span_id"));
        span.setParentSpanId(readText(row, "parent_span_id"));
        span.setSpanName(defaultText(readText(row, "span_name"), readText(row, "name")));
        span.setServiceName(defaultText(readText(row, "service_name"), resourceAttributes.get("service.name")));
        span.setStatus(status);
        span.setSpanKind(readText(row, "span_kind"));
        span.setStatusMessage(readText(row, "span_status_message"));
        span.setTraceState(readText(row, "trace_state"));
        span.setScopeName(readText(row, "scope_name"));
        span.setScopeVersion(readText(row, "scope_version"));
        span.setDurationNanos(readNonNegativeLong(row, "duration_nano"));
        span.setStartTime(readTimestamp(row, "timestamp"));
        span.setHighlighted(isErrorStatus(status));
        span.setResourceAttributes(resourceAttributes);
        span.setSpanAttributes(spanAttributes);
        span.setEvents(parseSpanEvents(row.get("span_events")));
        span.setLinks(parseSpanLinks(row.get("span_links")));
        span.setCodeNavigationHint(null);
        return span;
    }

    String readText(Map<String, Object> row, String key) {
        return trimText(Objects.toString(row.get(key), null));
    }

    Long readLong(Map<String, Object> row, String key) {
        return coerceLong(row.get(key));
    }

    Long readNonNegativeLong(Map<String, Object> row, String key) {
        Long value = readLong(row, key);
        return value == null ? null : Math.max(0L, value);
    }

    Double readDoubleValue(Map<String, Object> row, String... keys) {
        if (row == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key) || !row.containsKey(key)) {
                continue;
            }
            Object value = row.get(key);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            String text = trimText(Objects.toString(value, null));
            if (!StringUtils.hasText(text)) {
                continue;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                // Try the next key.
            }
        }
        return null;
    }

    Integer readIntValue(Map<String, Object> row, String... keys) {
        Long value = readLongValue(row, keys);
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return value.intValue();
    }

    Long readTimestamp(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toEpochMilli();
        }
        if (value instanceof java.util.Date date) {
            return date.getTime();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof ZonedDateTime dateTime) {
            return dateTime.toInstant().toEpochMilli();
        }
        if (value instanceof Number number) {
            return normalizeEpochMillis(number.longValue());
        }
        String text = trimText(Objects.toString(value, null));
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (text.matches("-?\\d+")) {
            Long numeric = coerceLong(text);
            return numeric == null ? null : normalizeEpochMillis(numeric);
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
            // Try offset and local date time fallbacks.
        }
        String normalizedText = text.replace(' ', 'T');
        try {
            return OffsetDateTime.parse(normalizedText).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // Try local date time fallback.
        }
        try {
            return LocalDateTime.parse(normalizedText).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    String normalizeStatus(String rawStatus) {
        String normalized = trimText(rawStatus);
        if (normalized == null) {
            return "unknown";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || "2".equals(lower)) {
            return "error";
        }
        if (lower.contains("ok") || "1".equals(lower) || lower.contains("unset") || "0".equals(lower)) {
            return "ok";
        }
        return lower;
    }

    boolean isErrorStatus(String status) {
        return "error".equalsIgnoreCase(trimText(status));
    }

    private List<TraceSpanEventDto> parseSpanEvents(Object rawValue) {
        List<Map<String, Object>> items = parseJsonObjectList(rawValue);
        if (CollectionUtils.isEmpty(items)) {
            return Collections.emptyList();
        }
        List<TraceSpanEventDto> events = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (CollectionUtils.isEmpty(item)) {
                continue;
            }
            events.add(new TraceSpanEventDto(
                    readLongValue(item, "time_unix_nano", "timeUnixNano"),
                    defaultText(readTextValue(item, "name"), readTextValue(item, "event_name")),
                    readObjectMap(item, "attributes"),
                    readNonNegativeIntValue(item, "dropped_attributes_count", "droppedAttributesCount")
            ));
        }
        return events;
    }

    private List<TraceSpanLinkDto> parseSpanLinks(Object rawValue) {
        List<Map<String, Object>> items = parseJsonObjectList(rawValue);
        if (CollectionUtils.isEmpty(items)) {
            return Collections.emptyList();
        }
        List<TraceSpanLinkDto> links = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (CollectionUtils.isEmpty(item)) {
                continue;
            }
            links.add(new TraceSpanLinkDto(
                    defaultText(readTextValue(item, "trace_id"), readTextValue(item, "traceId")),
                    defaultText(readTextValue(item, "span_id"), readTextValue(item, "spanId")),
                    defaultText(readTextValue(item, "trace_state"), readTextValue(item, "traceState")),
                    readObjectMap(item, "attributes"),
                    readNonNegativeIntValue(item, "dropped_attributes_count", "droppedAttributesCount")
            ));
        }
        return links;
    }

    private List<Map<String, Object>> parseJsonObjectList(Object rawValue) {
        if (rawValue == null) {
            return Collections.emptyList();
        }
        try {
            if (rawValue instanceof String rawText) {
                String normalized = trimText(rawText);
                if (!StringUtils.hasText(normalized)) {
                    return Collections.emptyList();
                }
                return JSON_MAPPER.readValue(normalized, new TypeReference<>() {
                });
            }
            return JSON_MAPPER.convertValue(rawValue, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.debug("Parse trace json list failed, value={}, message={}", rawValue, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, Object> readObjectMap(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = JSON_MAPPER.convertValue(value, new TypeReference<>() {
            });
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (IllegalArgumentException ex) {
            return Collections.emptyMap();
        }
    }

    String readTextValue(Map<String, Object> row, String key) {
        if (row == null || !row.containsKey(key)) {
            return null;
        }
        return trimText(Objects.toString(row.get(key), null));
    }

    Long readLongValue(Map<String, Object> row, String... keys) {
        if (row == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key) || !row.containsKey(key)) {
                continue;
            }
            Long value = coerceLong(row.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Integer readNonNegativeIntValue(Map<String, Object> row, String... keys) {
        Integer value = readIntValue(row, keys);
        return value == null ? null : Math.max(0, value);
    }

    private Map<String, String> parseAttributes(Object rawValue, String prefix, Map<String, Object> row) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawValue instanceof String rawText && StringUtils.hasText(rawText)) {
            try {
                Object parsed = JSON_MAPPER.readValue(rawText, new TypeReference<>() {
                });
                collectTraceAttributes(values, parsed);
            } catch (Exception ignored) {
                // Keep fallback scan below.
            }
        } else {
            collectTraceAttributes(values, rawValue);
        }
        row.forEach((key, value) -> {
            String normalizedKey = trimText(key);
            if (normalizedKey == null || !normalizedKey.startsWith(prefix)) {
                return;
            }
            String suffix = trimText(normalizedKey.substring(prefix.length()));
            String normalizedValue = trimText(Objects.toString(value, null));
            if (suffix != null && normalizedValue != null) {
                values.putIfAbsent(suffix, normalizedValue);
            }
        });
        return values;
    }

    private void collectTraceAttributes(Map<String, String> values, Object rawValue) {
        if (rawValue instanceof Map<?, ?> rawMap) {
            Object key = rawMap.get("key");
            if (key != null && rawMap.containsKey("value")) {
                putTraceAttribute(values, key, rawMap.get("value"));
                return;
            }
            rawMap.forEach((attributeKey, attributeValue) -> putTraceAttribute(values, attributeKey, attributeValue));
            return;
        }
        if (rawValue instanceof Iterable<?> items) {
            items.forEach(item -> collectTraceAttributes(values, item));
        }
    }

    private void putTraceAttribute(Map<String, String> values, Object key, Object value) {
        String normalizedKey = trimText(Objects.toString(key, null));
        String normalizedValue = traceAttributeValue(value);
        if (normalizedKey != null && normalizedValue != null) {
            values.put(normalizedKey, normalizedValue);
        }
    }

    private String traceAttributeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> rawMap) {
            for (String key : List.of("stringValue", "string_value", "intValue", "int_value",
                    "doubleValue", "double_value", "boolValue", "bool_value")) {
                if (rawMap.containsKey(key)) {
                    return traceAttributeValue(rawMap.get(key));
                }
            }
            if (rawMap.containsKey("value")) {
                return traceAttributeValue(rawMap.get("value"));
            }
        }
        return trimText(Objects.toString(value, null));
    }

    private Long coerceLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigInteger bigInteger) {
            return clampLong(bigInteger);
        }
        if (value instanceof BigDecimal bigDecimal) {
            return clampLong(bigDecimal);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = trimText(Objects.toString(value, null));
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            try {
                return clampLong(new BigInteger(text));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private Long clampLong(BigInteger value) {
        if (value.compareTo(LONG_MAX_VALUE) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(LONG_MIN_VALUE) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }

    private Long clampLong(BigDecimal value) {
        if (value.compareTo(LONG_MAX_DECIMAL) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(LONG_MIN_DECIMAL) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }

    private long normalizeEpochMillis(long numeric) {
        long magnitude = numeric == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(numeric);
        if (magnitude < 100_000_000_000L) {
            return numeric * 1_000L;
        }
        if (magnitude < 100_000_000_000_000L) {
            return numeric;
        }
        if (magnitude < 100_000_000_000_000_000L) {
            return numeric / 1_000L;
        }
        return numeric / 1_000_000L;
    }

    private String trimText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String defaultText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : trimText(fallback);
    }
}
