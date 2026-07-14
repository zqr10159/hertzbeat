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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.Test;

class OtlpTraceRequestNormalizerTest {

    @Test
    void coercesKnownNumericAttributesAndPreservesOtherStrings() {
        Span span = Span.newBuilder()
                .addAttributes(attribute("server.port", "443"))
                .addAttributes(attribute("custom.port", "8443"))
                .addAttributes(attribute("http.response.status_code", "503"))
                .addAttributes(attribute("server.address", "checkout.internal"))
                .addAttributes(attribute("client.port", "not-a-number"))
                .build();
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(span)))
                .build();

        Span normalized = new OtlpTraceRequestNormalizer().normalize(request)
                .getResourceSpans(0).getScopeSpans(0).getSpans(0);

        assertEquals(443L, normalized.getAttributes(0).getValue().getIntValue());
        assertEquals(8443L, normalized.getAttributes(1).getValue().getIntValue());
        assertEquals(503L, normalized.getAttributes(2).getValue().getIntValue());
        assertEquals("checkout.internal", normalized.getAttributes(3).getValue().getStringValue());
        assertEquals("not-a-number", normalized.getAttributes(4).getValue().getStringValue());
    }

    private KeyValue attribute(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }
}
