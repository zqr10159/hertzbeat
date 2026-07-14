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

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Normalizes trace attribute types expected by the Greptime trace schema.
 */
final class OtlpTraceRequestNormalizer {

    private static final Set<String> NUMERIC_ATTRIBUTE_KEYS = Set.of(
            "net.peer.port",
            "net.host.port",
            "network.peer.port",
            "network.local.port",
            "server.port",
            "client.port",
            "http.status_code",
            "http.response.status_code",
            "rpc.grpc.status_code"
    );

    ExportTraceServiceRequest normalize(ExportTraceServiceRequest request) {
        if (request == null || request.getResourceSpansCount() == 0) {
            return request;
        }
        ExportTraceServiceRequest.Builder requestBuilder = request.toBuilder().clearResourceSpans();
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            ResourceSpans.Builder resourceBuilder = resourceSpans.toBuilder().clearScopeSpans();
            for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
                ScopeSpans.Builder scopeBuilder = scopeSpans.toBuilder().clearSpans();
                for (Span span : scopeSpans.getSpansList()) {
                    scopeBuilder.addSpans(normalizeSpan(span));
                }
                resourceBuilder.addScopeSpans(scopeBuilder.build());
            }
            requestBuilder.addResourceSpans(resourceBuilder.build());
        }
        return requestBuilder.build();
    }

    private Span normalizeSpan(Span span) {
        if (span == null || span.getAttributesCount() == 0) {
            return span;
        }
        Span.Builder spanBuilder = span.toBuilder().clearAttributes();
        for (KeyValue attribute : span.getAttributesList()) {
            spanBuilder.addAttributes(normalizeAttribute(attribute));
        }
        return spanBuilder.build();
    }

    private KeyValue normalizeAttribute(KeyValue attribute) {
        if (attribute == null || !StringUtils.isNotBlank(attribute.getKey()) || !attribute.hasValue()) {
            return attribute;
        }
        if (!shouldCoerceToInt(attribute.getKey())) {
            return attribute;
        }
        AnyValue value = attribute.getValue();
        if (value.getValueCase() != AnyValue.ValueCase.STRING_VALUE) {
            return attribute;
        }
        String normalized = StringUtils.trimToNull(value.getStringValue());
        if (normalized == null || !normalized.matches("-?\\d+")) {
            return attribute;
        }
        try {
            long parsed = Long.parseLong(normalized);
            return attribute.toBuilder()
                    .setValue(AnyValue.newBuilder().setIntValue(parsed).build())
                    .build();
        } catch (NumberFormatException ex) {
            return attribute;
        }
    }

    private boolean shouldCoerceToInt(String key) {
        if (!StringUtils.isNotBlank(key)) {
            return false;
        }
        return NUMERIC_ATTRIBUTE_KEYS.contains(key) || StringUtils.endsWith(key, ".port");
    }
}
