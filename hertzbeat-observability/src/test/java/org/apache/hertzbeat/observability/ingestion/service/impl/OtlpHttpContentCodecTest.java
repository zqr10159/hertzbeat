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

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OtlpHttpContentCodecTest {

    private final OtlpHttpContentCodec codec = new OtlpHttpContentCodec();

    @Test
    void normalizesUpstreamMediaTypesAndNegotiatesWeightedResponses() {
        MediaType alternateProtobuf = MediaType.parseMediaType("application/protobuf");
        List<MediaType> weightedAccept = List.of(
                MediaType.parseMediaType("application/json;q=0.1"),
                MediaType.parseMediaType("application/protobuf;q=0.9"));

        assertEquals(OtlpHttpContentCodec.PROTOBUF_MEDIA_TYPE,
                codec.resolveUpstreamContentType(MediaType.APPLICATION_JSON));
        assertEquals(List.of(MediaType.parseMediaType("application/x-protobuf;q=0.9")),
                codec.resolveUpstreamAcceptTypes(List.of(MediaType.parseMediaType("application/protobuf;q=0.9"))));
        assertEquals(OtlpHttpContentCodec.PROTOBUF_MEDIA_TYPE,
                codec.resolveResponseContentType(alternateProtobuf, weightedAccept));
        assertEquals(MediaType.APPLICATION_JSON,
                codec.resolveResponseContentType(MediaType.APPLICATION_JSON, List.of()));
    }

    @Test
    void rendersDefaultResponsesAsJsonForEverySignal() {
        assertEquals("{}", renderJson(OtlpHttpContentCodec.Signal.METRICS,
                ExportMetricsServiceResponse.getDefaultInstance().toByteArray()));
        assertEquals("{}", renderJson(OtlpHttpContentCodec.Signal.LOGS,
                ExportLogsServiceResponse.getDefaultInstance().toByteArray()));
        assertEquals("{}", renderJson(OtlpHttpContentCodec.Signal.TRACES,
                ExportTraceServiceResponse.getDefaultInstance().toByteArray()));
    }

    @Test
    void rejectsMalformedSignalResponsesWithStableStatus() {
        StatusRuntimeException metricsFailure = assertThrows(StatusRuntimeException.class,
                () -> codec.validateResponseBody(new byte[] {(byte) 0xFF},
                        OtlpHttpContentCodec.Signal.METRICS));
        StatusRuntimeException traceFailure = assertThrows(StatusRuntimeException.class,
                () -> codec.validateResponseBody(new byte[] {(byte) 0xFF},
                        OtlpHttpContentCodec.Signal.TRACES));

        assertEquals(Status.Code.INTERNAL, metricsFailure.getStatus().getCode());
        assertEquals("OTLP metrics response is malformed.", metricsFailure.getStatus().getDescription());
        assertEquals("OTLP trace response is malformed.", traceFailure.getStatus().getDescription());
    }

    private String renderJson(OtlpHttpContentCodec.Signal signal, byte[] body) {
        return new String(codec.responseBodyForClient(body, signal, MediaType.APPLICATION_JSON),
                StandardCharsets.UTF_8).replaceAll("\\s+", "");
    }
}
