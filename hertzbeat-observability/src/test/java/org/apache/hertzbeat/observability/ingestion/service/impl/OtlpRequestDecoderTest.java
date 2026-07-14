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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OtlpRequestDecoderTest {

    private final OtlpRequestDecoder decoder = new OtlpRequestDecoder();

    @Test
    void decodesProtobufMetricsPayload() {
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder().build();

        assertEquals(request, decoder.decodeMetrics(request.toByteArray(), MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    void decodesJsonTracePayloadWithHexIdentifiers() {
        String traceId = "00112233445566778899aabbccddeeff";
        String spanId = "0011223344556677";
        String payload = """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{
                  "traceId":"%s","spanId":"%s","name":"checkout",
                  "kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1","endTimeUnixNano":"2"
                }]}]}]}
                """.formatted(traceId, spanId);

        var request = decoder.decodeTraces(payload.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON);
        var span = request.getResourceSpans(0).getScopeSpans(0).getSpans(0);

        assertEquals(ByteString.copyFrom(HexFormat.of().parseHex(traceId)), span.getTraceId());
        assertEquals(ByteString.copyFrom(HexFormat.of().parseHex(spanId)), span.getSpanId());
    }

    @Test
    void rejectsMalformedMetricsPayloadWithStableGrpcStatus() {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> decoder.decodeMetrics("not-protobuf".getBytes(StandardCharsets.UTF_8),
                        MediaType.APPLICATION_OCTET_STREAM));

        assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode());
        assertEquals("Malformed OTLP metrics payload.", exception.getStatus().getDescription());
    }
}
