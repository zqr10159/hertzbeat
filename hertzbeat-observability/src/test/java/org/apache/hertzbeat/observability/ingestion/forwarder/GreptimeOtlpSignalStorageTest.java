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

package org.apache.hertzbeat.observability.ingestion.forwarder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import java.util.List;
import org.apache.hertzbeat.observability.ingestion.storage.OtlpSignalStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class GreptimeOtlpSignalStorageTest {

    private final GreptimeOtlpForwarder forwarder = mock(GreptimeOtlpForwarder.class);
    private final OtlpSignalStorage storage = new GreptimeOtlpSignalStorage(forwarder);

    @Test
    void writesMetricsWithGreptimePromotionHeaders() {
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.getDefaultInstance();
        byte[] responseBody = ExportMetricsServiceResponse.getDefaultInstance().toByteArray();
        when(forwarder.forwardProtobuf(eq("/v1/otlp/v1/metrics"), eq(request.toByteArray()), any(HttpHeaders.class)))
                .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

        byte[] response = storage.writeMetrics(request);

        assertArrayEquals(responseBody, response);
        verify(forwarder).forwardProtobuf(eq("/v1/otlp/v1/metrics"), eq(request.toByteArray()),
                org.mockito.ArgumentMatchers.argThat(headers -> {
                    assertEquals(MediaType.parseMediaType("application/x-protobuf"), headers.getContentType());
                    assertEquals(List.of(MediaType.parseMediaType("application/x-protobuf")), headers.getAccept());
                    assertEquals("false", headers.getFirst("X-Greptime-OTLP-Metric-Promote-All-Resource-Attrs"));
                    assertEquals("false", headers.getFirst("X-Greptime-OTLP-Metric-Promote-Scope-Attrs"));
                    return true;
                }));
    }

    @Test
    void writesLogsThroughTheGreptimeLogAdapter() {
        ExportLogsServiceRequest request = ExportLogsServiceRequest.getDefaultInstance();
        byte[] responseBody = new byte[] {1, 2, 3};
        when(forwarder.forwardLogsProtobuf(request.toByteArray()))
                .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

        assertArrayEquals(responseBody, storage.writeLogs(request));
        verify(forwarder).forwardLogsProtobuf(request.toByteArray());
    }

    @Test
    void writesTracesWithGreptimeTableAndPipelineHeaders() {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.getDefaultInstance();
        byte[] responseBody = ExportTraceServiceResponse.getDefaultInstance().toByteArray();
        when(forwarder.forwardProtobuf(eq("/v1/otlp/v1/traces"), eq(request.toByteArray()), any(HttpHeaders.class)))
                .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

        byte[] response = storage.writeTraces(request);

        assertArrayEquals(responseBody, response);
        verify(forwarder).forwardProtobuf(eq("/v1/otlp/v1/traces"), eq(request.toByteArray()),
                org.mockito.ArgumentMatchers.argThat(headers -> {
                    assertEquals("hzb_traces", headers.getFirst("X-Greptime-Trace-Table-Name"));
                    assertEquals("greptime_trace_v1", headers.getFirst("X-Greptime-Pipeline-Name"));
                    return true;
                }));
    }
}
