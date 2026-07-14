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

import io.grpc.Status;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.util.List;
import org.apache.hertzbeat.observability.ingestion.semantic.OtlpResourceSemanticAttributes;
import org.apache.hertzbeat.observability.ingestion.storage.OtlpSignalStorage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * GreptimeDB implementation of the OTLP signal storage gateway.
 */
@Service
public class GreptimeOtlpSignalStorage implements OtlpSignalStorage {

    private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");
    private static final String METRICS_PATH = "/v1/otlp/v1/metrics";
    private static final String TRACES_PATH = "/v1/otlp/v1/traces";
    private static final String METRIC_PROMOTE_ALL_RESOURCE_ATTRIBUTES =
            "X-Greptime-OTLP-Metric-Promote-All-Resource-Attrs";
    private static final String METRIC_PROMOTE_RESOURCE_ATTRIBUTES =
            "X-Greptime-OTLP-Metric-Promote-Resource-Attrs";
    private static final String METRIC_PROMOTE_SCOPE_ATTRIBUTES =
            "X-Greptime-OTLP-Metric-Promote-Scope-Attrs";
    private static final String TRACE_TABLE_NAME = "X-Greptime-Trace-Table-Name";
    private static final String PIPELINE_NAME = "X-Greptime-Pipeline-Name";

    private final GreptimeOtlpForwarder forwarder;

    public GreptimeOtlpSignalStorage(GreptimeOtlpForwarder forwarder) {
        this.forwarder = forwarder;
    }

    @Override
    public byte[] writeMetrics(ExportMetricsServiceRequest request) {
        ExportMetricsServiceRequest safeRequest = request == null
                ? ExportMetricsServiceRequest.getDefaultInstance()
                : request;
        return responseBody(forwarder.forwardProtobuf(METRICS_PATH, safeRequest.toByteArray(), metricHeaders()));
    }

    @Override
    public byte[] writeLogs(ExportLogsServiceRequest request) {
        ExportLogsServiceRequest safeRequest = request == null
                ? ExportLogsServiceRequest.getDefaultInstance()
                : request;
        return responseBody(forwarder.forwardLogsProtobuf(safeRequest.toByteArray()));
    }

    @Override
    public byte[] writeTraces(ExportTraceServiceRequest request) {
        ExportTraceServiceRequest safeRequest = request == null
                ? ExportTraceServiceRequest.getDefaultInstance()
                : request;
        return responseBody(forwarder.forwardProtobuf(TRACES_PATH, safeRequest.toByteArray(), traceHeaders()));
    }

    private HttpHeaders metricHeaders() {
        HttpHeaders headers = protobufHeaders();
        headers.set(METRIC_PROMOTE_ALL_RESOURCE_ATTRIBUTES, Boolean.FALSE.toString());
        headers.set(METRIC_PROMOTE_RESOURCE_ATTRIBUTES,
                String.join(";", OtlpResourceSemanticAttributes.GREPTIME_METRIC_PROMOTED_RESOURCE_KEYS));
        headers.set(METRIC_PROMOTE_SCOPE_ATTRIBUTES, Boolean.FALSE.toString());
        return headers;
    }

    private HttpHeaders traceHeaders() {
        HttpHeaders headers = protobufHeaders();
        headers.set(TRACE_TABLE_NAME, "hzb_traces");
        headers.set(PIPELINE_NAME, "greptime_trace_v1");
        return headers;
    }

    private HttpHeaders protobufHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(PROTOBUF);
        headers.setAccept(List.of(PROTOBUF));
        return headers;
    }

    private byte[] responseBody(ResponseEntity<byte[]> response) {
        if (response == null) {
            throw Status.UNAVAILABLE.withDescription("OTLP backend returned no response.").asRuntimeException();
        }
        return response.getBody() == null ? new byte[0] : response.getBody();
    }
}
