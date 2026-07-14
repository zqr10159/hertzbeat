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

package org.apache.hertzbeat.observability.ingestion.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Decodes OTLP HTTP payloads without applying enrichment or forwarding rules.
 */
@Component
final class OtlpRequestDecoder {

    private static final Set<String> OTLP_HEX_ID_FIELDS = Set.of("traceId", "spanId", "parentSpanId");

    private final ObjectMapper objectMapper = new ObjectMapper();

    ExportMetricsServiceRequest decodeMetrics(byte[] content, MediaType contentType) {
        try {
            if (isJson(contentType)) {
                ExportMetricsServiceRequest.Builder builder = ExportMetricsServiceRequest.newBuilder();
                JsonFormat.parser().ignoringUnknownFields()
                        .merge(normalizeJson(new String(content, StandardCharsets.UTF_8)), builder);
                return builder.build();
            }
            return ExportMetricsServiceRequest.parseFrom(content);
        } catch (InvalidProtocolBufferException ex) {
            throw io.grpc.Status.INVALID_ARGUMENT.withDescription("Malformed OTLP metrics payload.")
                    .withCause(ex).asRuntimeException();
        }
    }

    ExportTraceServiceRequest decodeTraces(byte[] content, MediaType contentType) {
        try {
            if (isJson(contentType)) {
                ExportTraceServiceRequest.Builder builder = ExportTraceServiceRequest.newBuilder();
                JsonFormat.parser().ignoringUnknownFields()
                        .merge(normalizeJson(new String(content, StandardCharsets.UTF_8)), builder);
                return builder.build();
            }
            return ExportTraceServiceRequest.parseFrom(content);
        } catch (InvalidProtocolBufferException ex) {
            throw io.grpc.Status.INVALID_ARGUMENT.withDescription("Malformed OTLP trace payload.")
                    .withCause(ex).asRuntimeException();
        }
    }

    private boolean isJson(MediaType contentType) {
        return contentType != null && MediaType.APPLICATION_JSON.includes(contentType);
    }

    private String normalizeJson(String content) throws InvalidProtocolBufferException {
        try {
            JsonNode root = objectMapper.readTree(content);
            normalizeHexEncodedIds(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new InvalidProtocolBufferException("Failed to normalize OTLP JSON: " + ex.getMessage());
        }
    }

    private void normalizeHexEncodedIds(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            objectNode.fieldNames().forEachRemaining(fieldName -> {
                JsonNode child = objectNode.get(fieldName);
                if (OTLP_HEX_ID_FIELDS.contains(fieldName) && child != null && child.isTextual()) {
                    String normalized = tryConvertHexToBase64(child.asText());
                    if (normalized != null) {
                        objectNode.put(fieldName, normalized);
                    }
                } else {
                    normalizeHexEncodedIds(child);
                }
            });
            return;
        }
        if (node.isArray()) {
            node.forEach(this::normalizeHexEncodedIds);
        }
    }

    private String tryConvertHexToBase64(String value) {
        if (StringUtils.isBlank(value) || (value.length() & 1) != 0) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return null;
            }
        }
        try {
            return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
