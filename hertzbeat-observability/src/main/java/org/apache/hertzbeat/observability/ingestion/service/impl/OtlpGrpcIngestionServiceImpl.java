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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hertzbeat.common.observability.gateway.ObservabilitySignalIntakeGateway;
import org.apache.hertzbeat.observability.ingestion.service.OtlpGrpcIngestionService;
import org.apache.hertzbeat.observability.ingestion.adapter.OtlpLogProtocolAdapter;
import org.apache.hertzbeat.observability.ingestion.audit.OtlpIngestionAuditService;
import org.apache.hertzbeat.observability.ingestion.enricher.OtlpCorrelationContext;
import org.apache.hertzbeat.observability.ingestion.enricher.OtlpCorrelationEnricher;
import org.apache.hertzbeat.observability.ingestion.enricher.OtlpEntityIdentityResolver;
import org.apache.hertzbeat.observability.ingestion.error.OtlpIngestionErrorResponseFactory;
import org.apache.hertzbeat.observability.ingestion.governance.OtlpIngestionGovernanceService;
import org.apache.hertzbeat.observability.ingestion.quota.OtlpIngestionQuotaService;
import org.apache.hertzbeat.observability.ingestion.redaction.OtlpIngestionRedactionService;
import org.apache.hertzbeat.observability.ingestion.redaction.OtlpProtobufRedactor;
import org.apache.hertzbeat.observability.ingestion.security.OtlpIngestionRequestContextResolver;
import org.apache.hertzbeat.observability.ingestion.storage.OtlpSignalStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Unified OTLP ingestion implementation for HTTP and gRPC.
 */
@Slf4j
@Service
public class OtlpGrpcIngestionServiceImpl implements OtlpGrpcIngestionService {

    private static final String CONTENT_ENCODING = "Content-Encoding";
    private static final String CONTENT_ENCODING_GZIP = "gzip";
    private static final int GZIP_DECOMPRESSION_BUFFER_BYTES = 8192;
    private static final int OTLP_TRACE_ID_BYTES = 16;
    private static final int OTLP_SPAN_ID_BYTES = 8;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String OTLP_METRIC_COMPATIBILITY = "otlp.metric.compatibility";
    private static final String OTLP_METRIC_COMPATIBILITY_REASON = "otlp.metric.compatibility.reason";
    private static final String OTLP_METRIC_GREPTIME_COMPATIBILITY = "otlp.metric.greptime.compatibility";
    private static final String OTLP_METRIC_GREPTIME_REASON = "otlp.metric.greptime.reason";
    private static final String OTLP_METRIC_FACADE_COMPATIBILITY = "otlp.metric.facade.compatibility";
    private static final String OTLP_METRIC_FACADE_REASON = "otlp.metric.facade.reason";
    private static final String OTLP_METRIC_AGGREGATION_TEMPORALITY = "otlp.metric.aggregation_temporality";
    private static final String OTLP_METRIC_MONOTONIC = "otlp.metric.monotonic";
    private static final String OTLP_METRIC_START_TIME_MILLIS = "otlp.metric.start_time_unix_millis";
    private static final String OTLP_METRIC_END_TIME_MILLIS = "otlp.metric.end_time_unix_millis";
    private static final String OTLP_METRIC_DATA_POINT_COUNT = "otlp.metric.data_point_count";
    private static final String OTLP_METRIC_HISTOGRAM_BUCKET_COUNTS = "otlp.metric.histogram.bucket_counts";
    private static final String OTLP_METRIC_HISTOGRAM_EXPLICIT_BOUNDS = "otlp.metric.histogram.explicit_bounds";
    private static final String OTLP_METRIC_HISTOGRAM_COUNT = "otlp.metric.histogram.count";
    private static final String OTLP_METRIC_HISTOGRAM_SUM = "otlp.metric.histogram.sum";
    private static final String OTLP_METRIC_SUMMARY_COUNT = "otlp.metric.summary.count";
    private static final String OTLP_METRIC_SUMMARY_SUM = "otlp.metric.summary.sum";
    private static final String OTLP_METRIC_SUMMARY_QUANTILES = "otlp.metric.summary.quantiles";
    private static final String OTLP_METRIC_EXP_SCALE = "otlp.metric.exponential_histogram.scale";
    private static final String OTLP_METRIC_EXP_ZERO_COUNT = "otlp.metric.exponential_histogram.zero_count";
    private static final String OTLP_METRIC_EXP_ZERO_THRESHOLD = "otlp.metric.exponential_histogram.zero_threshold";
    private static final String OTLP_METRIC_EXP_POSITIVE = "otlp.metric.exponential_histogram.positive";
    private static final String OTLP_METRIC_EXP_NEGATIVE = "otlp.metric.exponential_histogram.negative";
    private final OtlpLogProtocolAdapter otlpLogProtocolAdapter;
    private final OtlpSignalStorage signalStorage;
    private final OtlpCorrelationEnricher otlpCorrelationEnricher;
    private final OtlpIngestionErrorResponseFactory errorResponseFactory;
    private final OtlpIngestionRequestContextResolver requestContextResolver;
    private final OtlpIngestionAuditService auditService;
    private final OtlpIngestionGovernanceService governanceService;
    private final OtlpIngestionQuotaService quotaService;
    private final ObservabilitySignalIntakeGateway observabilitySignalIntakeGateway;
    private final OtlpEntityIdentityResolver otlpEntityIdentityResolver;
    private final OtlpProtobufRedactor protobufRedactor =
            new OtlpProtobufRedactor(new OtlpIngestionRedactionService());
    private final OtlpRequestDecoder requestDecoder;
    private final OtlpTraceRequestNormalizer traceRequestNormalizer = new OtlpTraceRequestNormalizer();
    private final OtlpHttpContentCodec httpContentCodec = new OtlpHttpContentCodec();

    @Autowired
    public OtlpGrpcIngestionServiceImpl(OtlpLogProtocolAdapter otlpLogProtocolAdapter,
                                        OtlpSignalStorage signalStorage,
                                        OtlpCorrelationEnricher otlpCorrelationEnricher,
                                        OtlpIngestionErrorResponseFactory errorResponseFactory,
                                        OtlpIngestionRequestContextResolver requestContextResolver,
                                        OtlpIngestionAuditService auditService,
                                        OtlpIngestionGovernanceService governanceService,
                                        OtlpIngestionQuotaService quotaService,
                                        @Qualifier("telemetryIntakeServiceImpl")
                                        ObservabilitySignalIntakeGateway observabilitySignalIntakeGateway,
                                        OtlpEntityIdentityResolver otlpEntityIdentityResolver) {
        this(otlpLogProtocolAdapter, signalStorage, otlpCorrelationEnricher, errorResponseFactory,
                requestContextResolver, auditService, governanceService, quotaService,
                observabilitySignalIntakeGateway, otlpEntityIdentityResolver, new OtlpRequestDecoder());
    }

    OtlpGrpcIngestionServiceImpl(OtlpLogProtocolAdapter otlpLogProtocolAdapter,
                                 OtlpSignalStorage signalStorage,
                                 OtlpCorrelationEnricher otlpCorrelationEnricher,
                                 OtlpIngestionErrorResponseFactory errorResponseFactory,
                                 OtlpIngestionRequestContextResolver requestContextResolver,
                                 OtlpIngestionAuditService auditService,
                                 OtlpIngestionGovernanceService governanceService,
                                 OtlpIngestionQuotaService quotaService,
                                 ObservabilitySignalIntakeGateway observabilitySignalIntakeGateway,
                                 OtlpEntityIdentityResolver otlpEntityIdentityResolver,
                                 OtlpRequestDecoder requestDecoder) {
        this.otlpLogProtocolAdapter = otlpLogProtocolAdapter;
        this.signalStorage = signalStorage;
        this.otlpCorrelationEnricher = otlpCorrelationEnricher;
        this.errorResponseFactory = errorResponseFactory;
        this.requestContextResolver = requestContextResolver;
        this.auditService = auditService;
        this.governanceService = governanceService;
        this.quotaService = quotaService;
        this.observabilitySignalIntakeGateway = observabilitySignalIntakeGateway;
        this.otlpEntityIdentityResolver = otlpEntityIdentityResolver;
        this.requestDecoder = requestDecoder;
    }

    @Override
    public ResponseEntity<byte[]> ingestMetricsHttp(byte[] content, HttpHeaders requestHeaders) {
        long startedAtNanos = System.nanoTime();
        HttpHeaders safeRequestHeaders = safeHeaders(requestHeaders);
        byte[] safeContent = safeContent(content);
        MediaType contentType = safeRequestHeaders.getContentType();
        OtlpCorrelationContext correlationContext = requestContextResolver.currentCorrelationContext();
        long requestBytes = requestBytes(content);
        Long signalItems = null;
        try {
            quotaService.checkRequestBytes("metrics", "http", requestBytes);
            byte[] normalizedContent = normalizeHttpContentForQuota("metrics", "http", safeContent, safeRequestHeaders);
            ExportMetricsServiceRequest request = normalizeAndEnrichMetricRequest(
                    requestDecoder.decodeMetrics(normalizedContent, contentType), correlationContext);
            signalItems = quotaService.countMetricItems(request);
            quotaService.checkMetricItems("http", signalItems);
            OtlpIngestionGovernanceService.Decision governanceDecision =
                    governanceService.evaluateMetrics("http", request);
            if (governanceDecision.dropped()) {
                auditService.recordDropped("metrics", "http", correlationContext, requestBytes, signalItems,
                        governanceDecision.reason(), durationMillis(startedAtNanos));
                return emptySignalHttpSuccess(contentType, safeRequestHeaders.getAccept(), false);
            }
            ResponseEntity<byte[]> response = signalHttpSuccess(
                    signalStorage.writeMetrics(request), contentType, safeRequestHeaders.getAccept(), false);
            if (response.getStatusCode().is2xxSuccessful()) {
                recordMetricIntake(request);
                auditService.recordAccepted("metrics", "http", correlationContext, requestBytes, signalItems,
                        durationMillis(startedAtNanos));
            }
            return response;
        } catch (StatusRuntimeException ex) {
            auditService.recordRejected("metrics", "http", correlationContext, requestBytes, ex, signalItems,
                    durationMillis(startedAtNanos));
            return errorResponseFactory.httpErrorResponse(contentType, safeRequestHeaders.getAccept(), ex);
        }
    }

    @Override
    public ResponseEntity<byte[]> ingestLogsHttp(byte[] content, HttpHeaders requestHeaders) {
        long startedAtNanos = System.nanoTime();
        HttpHeaders safeRequestHeaders = safeHeaders(requestHeaders);
        byte[] safeContent = safeContent(content);
        MediaType contentType = safeRequestHeaders.getContentType();
        OtlpCorrelationContext correlationContext = requestContextResolver.currentCorrelationContext();
        long requestBytes = requestBytes(content);
        Long signalItems = null;
        try {
            quotaService.checkRequestBytes("logs", "http", requestBytes);
            byte[] normalizedContent = normalizeHttpContentForQuota("logs", "http", safeContent, safeRequestHeaders);
            HttpHeaders normalizedHeaders = withoutContentEncoding(safeRequestHeaders);
            byte[] enrichedContent = otlpCorrelationEnricher.enrichLogsHttp(
                    normalizedContent, normalizedHeaders, correlationContext);
            ExportLogsServiceRequest enrichedRequest = ExportLogsServiceRequest.parseFrom(enrichedContent);
            ExportLogsServiceRequest resolvedRequest = otlpEntityIdentityResolver.enrichLogs(
                    enrichedRequest, correlationContext.workspaceId());
            ExportLogsServiceRequest redactedRequest = protobufRedactor.redactLogs(resolvedRequest);
            signalItems = quotaService.countLogItems(redactedRequest);
            quotaService.checkLogItems("http", signalItems);
            OtlpIngestionGovernanceService.Decision governanceDecision =
                    governanceService.evaluateLogs("http", redactedRequest);
            if (governanceDecision.dropped()) {
                auditService.recordDropped("logs", "http", correlationContext, requestBytes, signalItems,
                        governanceDecision.reason(), durationMillis(startedAtNanos));
                return emptyLogsHttpSuccess(contentType, safeRequestHeaders.getAccept());
            }
            byte[] storageResponse = signalStorage.writeLogs(redactedRequest);
            ResponseEntity<byte[]> successResponse =
                    logsHttpSuccess(contentType, safeRequestHeaders.getAccept(), storageResponse);
            publishRealtimeSignalsBestEffort(redactedRequest);
            auditService.recordAccepted("logs", "http", correlationContext, requestBytes, signalItems,
                    durationMillis(startedAtNanos));
            return successResponse;
        } catch (StatusRuntimeException ex) {
            auditService.recordRejected("logs", "http", correlationContext, requestBytes, ex, signalItems,
                    durationMillis(startedAtNanos));
            return errorResponseFactory.httpErrorResponse(contentType, safeRequestHeaders.getAccept(), ex);
        } catch (IllegalArgumentException ex) {
            auditService.recordRejected("logs", "http", correlationContext, requestBytes,
                    io.grpc.Status.Code.INVALID_ARGUMENT, defaultErrorMessage(ex), signalItems,
                    durationMillis(startedAtNanos));
            return errorResponseFactory.httpErrorResponse(
                    contentType, safeRequestHeaders.getAccept(), HttpStatus.BAD_REQUEST, defaultErrorMessage(ex));
        } catch (InvalidProtocolBufferException ex) {
            auditService.recordRejected("logs", "http", correlationContext, requestBytes,
                    io.grpc.Status.Code.INVALID_ARGUMENT, "Malformed OTLP logs payload.", signalItems,
                    durationMillis(startedAtNanos));
            return errorResponseFactory.httpErrorResponse(
                    contentType, safeRequestHeaders.getAccept(), HttpStatus.BAD_REQUEST,
                    "Malformed OTLP logs payload.");
        } catch (Exception ex) {
            log.error("Unexpected error ingesting OTLP HTTP logs: {}", ex.getMessage(), ex);
            auditService.recordRejected("logs", "http", correlationContext, requestBytes,
                    io.grpc.Status.Code.INTERNAL, defaultErrorMessage(ex), signalItems,
                    durationMillis(startedAtNanos));
            return errorResponseFactory.httpErrorResponse(
                    contentType, safeRequestHeaders.getAccept(), HttpStatus.INTERNAL_SERVER_ERROR,
                    defaultErrorMessage(ex));
        }
    }

    @Override
    public ResponseEntity<byte[]> ingestTracesHttp(byte[] content, HttpHeaders requestHeaders) {
        long startedAtNanos = System.nanoTime();
        HttpHeaders safeRequestHeaders = safeHeaders(requestHeaders);
        byte[] safeContent = safeContent(content);
        MediaType contentType = safeRequestHeaders.getContentType();
        OtlpCorrelationContext correlationContext = requestContextResolver.currentCorrelationContext();
        long requestBytes = requestBytes(content);
        Long signalItems = null;
        try {
            quotaService.checkRequestBytes("traces", "http", requestBytes);
            byte[] normalizedContent = normalizeHttpContentForQuota("traces", "http", safeContent, safeRequestHeaders);
            ExportTraceServiceRequest request = normalizeAndEnrichTraceRequest(
                    requestDecoder.decodeTraces(normalizedContent, contentType), correlationContext);
            signalItems = quotaService.countTraceItems(request);
            quotaService.checkTraceItems("http", signalItems);
            OtlpIngestionGovernanceService.Decision governanceDecision =
                    governanceService.evaluateTraces("http", request);
            if (governanceDecision.dropped()) {
                auditService.recordDropped("traces", "http", correlationContext, requestBytes, signalItems,
                        governanceDecision.reason(), durationMillis(startedAtNanos));
                return emptySignalHttpSuccess(contentType, safeRequestHeaders.getAccept(), true);
            }
            ResponseEntity<byte[]> response = signalHttpSuccess(
                    signalStorage.writeTraces(request), contentType, safeRequestHeaders.getAccept(), true);
            if (response.getStatusCode().is2xxSuccessful()) {
                recordTraceIntake(request);
                auditService.recordAccepted("traces", "http", correlationContext, requestBytes, signalItems,
                        durationMillis(startedAtNanos));
            }
            return response;
        } catch (StatusRuntimeException ex) {
            auditService.recordRejected("traces", "http", correlationContext, requestBytes, ex, signalItems,
                    durationMillis(startedAtNanos));
            return errorResponseFactory.httpErrorResponse(contentType, safeRequestHeaders.getAccept(), ex);
        }
    }

    @Override
    public ExportMetricsServiceResponse ingestMetricsGrpc(ExportMetricsServiceRequest request) {
        long startedAtNanos = System.nanoTime();
        OtlpCorrelationContext correlationContext = requestContextResolver.currentCorrelationContext();
        long requestBytes = requestBytes(request);
        Long signalItems = null;
        try {
            quotaService.checkRequestBytes("metrics", "grpc", requestBytes);
            ExportMetricsServiceRequest redactedRequest = normalizeAndEnrichMetricRequest(request, correlationContext);
            signalItems = quotaService.countMetricItems(redactedRequest);
            quotaService.checkMetricItems("grpc", signalItems);
            OtlpIngestionGovernanceService.Decision governanceDecision =
                    governanceService.evaluateMetrics("grpc", redactedRequest);
            if (governanceDecision.dropped()) {
                auditService.recordDropped("metrics", "grpc", correlationContext, requestBytes, signalItems,
                        governanceDecision.reason(), durationMillis(startedAtNanos));
                return ExportMetricsServiceResponse.getDefaultInstance();
            }
            byte[] response = signalStorage.writeMetrics(redactedRequest);
            ExportMetricsServiceResponse parsedResponse = response.length == 0
                    ? ExportMetricsServiceResponse.getDefaultInstance()
                    : ExportMetricsServiceResponse.parseFrom(response);
            recordMetricIntake(redactedRequest);
            auditService.recordAccepted("metrics", "grpc", correlationContext, requestBytes, signalItems,
                    durationMillis(startedAtNanos));
            return parsedResponse;
        } catch (StatusRuntimeException ex) {
            auditService.recordRejected("metrics", "grpc", correlationContext, requestBytes, ex, signalItems,
                    durationMillis(startedAtNanos));
            throw ex;
        } catch (InvalidProtocolBufferException e) {
            auditService.recordRejected("metrics", "grpc", correlationContext, requestBytes,
                    io.grpc.Status.Code.INTERNAL, "OTLP metrics response is malformed.", signalItems,
                    durationMillis(startedAtNanos));
            throw io.grpc.Status.INTERNAL.withDescription("OTLP metrics response is malformed.").withCause(e)
                    .asRuntimeException();
        }
    }

    @Override
    public ExportLogsServiceResponse ingestLogsGrpc(ExportLogsServiceRequest request) {
        long startedAtNanos = System.nanoTime();
        OtlpCorrelationContext correlationContext = requestContextResolver.currentCorrelationContext();
        long requestBytes = requestBytes(request);
        Long signalItems = null;
        try {
            quotaService.checkRequestBytes("logs", "grpc", requestBytes);
            ExportLogsServiceRequest enrichedRequest = otlpCorrelationEnricher.enrichLogs(
                    request, correlationContext);
            ExportLogsServiceRequest resolvedRequest = otlpEntityIdentityResolver.enrichLogs(
                    enrichedRequest, correlationContext.workspaceId());
            ExportLogsServiceRequest redactedRequest = protobufRedactor.redactLogs(resolvedRequest);
            signalItems = quotaService.countLogItems(redactedRequest);
            quotaService.checkLogItems("grpc", signalItems);
            OtlpIngestionGovernanceService.Decision governanceDecision =
                    governanceService.evaluateLogs("grpc", redactedRequest);
            if (governanceDecision.dropped()) {
                auditService.recordDropped("logs", "grpc", correlationContext, requestBytes, signalItems,
                        governanceDecision.reason(), durationMillis(startedAtNanos));
                return ExportLogsServiceResponse.getDefaultInstance();
            }
            byte[] response = signalStorage.writeLogs(redactedRequest);
            if (response == null) {
                throw io.grpc.Status.UNAVAILABLE.withDescription("OTLP backend returned no response.")
                        .asRuntimeException();
            }
            ExportLogsServiceResponse parsedResponse = response.length == 0
                    ? ExportLogsServiceResponse.getDefaultInstance()
                    : ExportLogsServiceResponse.parseFrom(response);
            publishRealtimeSignalsBestEffort(redactedRequest);
            auditService.recordAccepted("logs", "grpc", correlationContext, requestBytes, signalItems,
                    durationMillis(startedAtNanos));
            return parsedResponse;
        } catch (StatusRuntimeException ex) {
            auditService.recordRejected("logs", "grpc", correlationContext, requestBytes, ex, signalItems,
                    durationMillis(startedAtNanos));
            throw ex;
        } catch (InvalidProtocolBufferException ex) {
            auditService.recordRejected("logs", "grpc", correlationContext, requestBytes,
                    io.grpc.Status.Code.INTERNAL, "OTLP logs response is malformed.", signalItems,
                    durationMillis(startedAtNanos));
            throw io.grpc.Status.INTERNAL.withDescription("OTLP logs response is malformed.").withCause(ex)
                    .asRuntimeException();
        } catch (IllegalArgumentException ex) {
            auditService.recordRejected("logs", "grpc", correlationContext, requestBytes,
                    io.grpc.Status.Code.INVALID_ARGUMENT, defaultErrorMessage(ex), signalItems,
                    durationMillis(startedAtNanos));
            throw io.grpc.Status.INVALID_ARGUMENT.withDescription(defaultErrorMessage(ex)).withCause(ex)
                    .asRuntimeException();
        } catch (Exception ex) {
            auditService.recordRejected("logs", "grpc", correlationContext, requestBytes,
                    io.grpc.Status.Code.INTERNAL, defaultErrorMessage(ex), signalItems,
                    durationMillis(startedAtNanos));
            throw io.grpc.Status.INTERNAL.withDescription(defaultErrorMessage(ex)).withCause(ex)
                    .asRuntimeException();
        }
    }

    @Override
    public ExportTraceServiceResponse ingestTracesGrpc(ExportTraceServiceRequest request) {
        long startedAtNanos = System.nanoTime();
        OtlpCorrelationContext correlationContext = requestContextResolver.currentCorrelationContext();
        long requestBytes = requestBytes(request);
        Long signalItems = null;
        try {
            quotaService.checkRequestBytes("traces", "grpc", requestBytes);
            ExportTraceServiceRequest enrichedRequest = normalizeAndEnrichTraceRequest(request, correlationContext);
            signalItems = quotaService.countTraceItems(enrichedRequest);
            quotaService.checkTraceItems("grpc", signalItems);
            OtlpIngestionGovernanceService.Decision governanceDecision =
                    governanceService.evaluateTraces("grpc", enrichedRequest);
            if (governanceDecision.dropped()) {
                auditService.recordDropped("traces", "grpc", correlationContext, requestBytes, signalItems,
                        governanceDecision.reason(), durationMillis(startedAtNanos));
                return ExportTraceServiceResponse.getDefaultInstance();
            }
            byte[] response = signalStorage.writeTraces(enrichedRequest);
            ExportTraceServiceResponse parsedResponse = response.length == 0
                    ? ExportTraceServiceResponse.getDefaultInstance()
                    : ExportTraceServiceResponse.parseFrom(response);
            recordTraceIntake(enrichedRequest);
            auditService.recordAccepted("traces", "grpc", correlationContext, requestBytes, signalItems,
                    durationMillis(startedAtNanos));
            return parsedResponse;
        } catch (StatusRuntimeException ex) {
            auditService.recordRejected("traces", "grpc", correlationContext, requestBytes, ex, signalItems,
                    durationMillis(startedAtNanos));
            throw ex;
        } catch (InvalidProtocolBufferException e) {
            auditService.recordRejected("traces", "grpc", correlationContext, requestBytes,
                    io.grpc.Status.Code.INTERNAL, "OTLP trace response is malformed.", signalItems,
                    durationMillis(startedAtNanos));
            throw io.grpc.Status.INTERNAL.withDescription("OTLP trace response is malformed.").withCause(e)
                    .asRuntimeException();
        }
    }

    @Override
    public List<String> getGrpcSupportedSignals() {
        return List.of("metrics", "logs", "traces");
    }

    private ResponseEntity<byte[]> emptyLogsHttpSuccess(MediaType requestContentType, List<MediaType> acceptTypes) {
        return logsHttpSuccess(requestContentType, acceptTypes,
                ExportLogsServiceResponse.getDefaultInstance().toByteArray());
    }

    private ResponseEntity<byte[]> logsHttpSuccess(MediaType requestContentType, List<MediaType> acceptTypes,
                                                   byte[] upstreamBody) {
        MediaType responseContentType = httpContentCodec.resolveResponseContentType(
                requestContentType, acceptTypes);
        byte[] responseBody = httpContentCodec.responseBodyForClient(
                upstreamBody, OtlpHttpContentCodec.Signal.LOGS, responseContentType);
        return ResponseEntity.ok()
                .contentType(responseContentType)
                .body(responseBody);
    }

    private byte[] normalizeHttpContentForQuota(String signal, String protocol, byte[] content, HttpHeaders headers) {
        if (!isGzipEncoded(headers)) {
            return content;
        }
        return decompressGzip(content, signal, protocol);
    }

    private HttpHeaders safeHeaders(HttpHeaders headers) {
        return headers == null ? new HttpHeaders() : headers;
    }

    private byte[] safeContent(byte[] content) {
        return content == null ? new byte[0] : content;
    }

    private HttpHeaders withoutContentEncoding(HttpHeaders headers) {
        HttpHeaders normalizedHeaders = new HttpHeaders();
        if (headers != null) {
            normalizedHeaders.putAll(headers);
            normalizedHeaders.remove(CONTENT_ENCODING);
            normalizedHeaders.remove(HttpHeaders.CONTENT_ENCODING);
        }
        return normalizedHeaders;
    }

    private boolean isGzipEncoded(HttpHeaders headers) {
        List<String> contentEncodings = headers == null ? null : headers.get(CONTENT_ENCODING);
        return contentEncodings != null && contentEncodings.stream().anyMatch(this::isGzipContentEncoding);
    }

    private byte[] maybeDecompress(byte[] content, HttpHeaders headers) {
        if (content == null || content.length == 0 || headers == null) {
            return content;
        }
        if (!isGzipEncoded(headers)) {
            return content;
        }
        return decompressGzip(content, null, null);
    }

    private boolean isGzipContentEncoding(String contentEncoding) {
        String[] encodings = StringUtils.split(contentEncoding, ',');
        if (encodings == null || encodings.length == 0) {
            return false;
        }
        for (String encoding : encodings) {
            if (StringUtils.equalsIgnoreCase(StringUtils.trimToEmpty(encoding), CONTENT_ENCODING_GZIP)) {
                return true;
            }
        }
        return false;
    }

    private byte[] decompressGzip(byte[] content, String signal, String protocol) {
        if (content == null || content.length == 0) {
            return content;
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
             GZIPInputStream gzipInputStream = new GZIPInputStream(inputStream);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[GZIP_DECOMPRESSION_BUFFER_BYTES];
            int read;
            while ((read = gzipInputStream.read(buffer)) != -1) {
                if (signal != null && protocol != null) {
                    quotaService.checkRequestBytes(signal, protocol, (long) outputStream.size() + read);
                }
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        } catch (StatusRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            String description = StringUtils.isNotBlank(signal) && StringUtils.isNotBlank(protocol)
                    ? "Malformed gzip-compressed OTLP " + signal + " " + protocol + " payload."
                    : "Malformed gzip-compressed OTLP payload.";
            throw io.grpc.Status.INVALID_ARGUMENT.withDescription(description)
                    .withCause(ex).asRuntimeException();
        }
    }

    private void recordMetricIntake(ExportMetricsServiceRequest request) {
        if (request == null) {
            return;
        }
        for (ResourceMetrics resourceMetrics : request.getResourceMetricsList()) {
            Map<String, String> resourceAttributes = requestContextResolver.withWorkspaceResourceAttributes(
                    toStringMap(resourceMetrics.getResource().getAttributesList()));
            for (ScopeMetrics scopeMetrics : resourceMetrics.getScopeMetricsList()) {
                for (Metric metric : scopeMetrics.getMetricsList()) {
                    List<MetricObservation> observations = extractMetricObservations(metric);
                    for (MetricObservation observation : observations) {
                        try {
                            observabilitySignalIntakeGateway.recordOtlpMetricIntake(
                                    resourceAttributes,
                                    observation.observedAt(),
                                    StringUtils.trimToNull(metric.getName()),
                                    observation.metricType(),
                                    StringUtils.trimToNull(metric.getUnit()),
                                    observation.value(),
                                    observation.attributes()
                            );
                        } catch (RuntimeException ex) {
                            log.warn("Failed to record OTLP metric intake read model for metric {}: {}",
                                    StringUtils.trimToNull(metric.getName()), ex.toString());
                        }
                    }
                }
            }
        }
    }

    private void recordTraceIntake(ExportTraceServiceRequest request) {
        if (request == null) {
            return;
        }
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            Map<String, String> resourceAttributes = requestContextResolver.withWorkspaceResourceAttributes(
                    toStringMap(resourceSpans.getResource().getAttributesList()));
            for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
                for (Span span : scopeSpans.getSpansList()) {
                    if (!hasValidTraceIdentifiers(span)) {
                        continue;
                    }
                    String traceId = HexFormat.of().formatHex(span.getTraceId().toByteArray());
                    String spanId = HexFormat.of().formatHex(span.getSpanId().toByteArray());
                    try {
                        observabilitySignalIntakeGateway.recordOtlpTraceIntake(
                                resourceAttributes,
                                nanosToMillis(span.getStartTimeUnixNano()),
                                traceId,
                                spanId,
                                StringUtils.trimToNull(span.getName()),
                                span.getStatus().getCode().name().toLowerCase(Locale.ROOT),
                                toStringMap(span.getAttributesList())
                        );
                    } catch (RuntimeException ex) {
                        log.warn("Failed to record OTLP trace intake read model for trace {} span {}: {}",
                                traceId, spanId, ex.toString());
                    }
                }
            }
        }
    }

    private boolean hasValidTraceIdentifiers(Span span) {
        return span != null
                && span.getTraceId().size() == OTLP_TRACE_ID_BYTES
                && span.getSpanId().size() == OTLP_SPAN_ID_BYTES
                && !isAllZero(span.getTraceId().toByteArray())
                && !isAllZero(span.getSpanId().toByteArray());
    }

    private boolean isAllZero(byte[] value) {
        if (value == null || value.length == 0) {
            return true;
        }
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    private void publishRealtimeSignalsBestEffort(ExportLogsServiceRequest request) {
        try {
            otlpLogProtocolAdapter.publishRealtimeSignals(request);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish OTLP log realtime signals: {}", ex.toString());
        }
    }

    private ResponseEntity<byte[]> signalHttpSuccess(byte[] protobufBody, MediaType requestContentType,
                                                     List<MediaType> acceptTypes, boolean traceSignal) {
        MediaType responseContentType = httpContentCodec.resolveResponseContentType(requestContentType, acceptTypes);
        byte[] responseBody = httpContentCodec.responseBodyForClient(
                protobufBody, signal(traceSignal), responseContentType);
        return ResponseEntity.ok()
                .contentType(responseContentType)
                .body(responseBody);
    }

    private ResponseEntity<byte[]> emptySignalHttpSuccess(MediaType requestContentType, List<MediaType> acceptTypes,
                                                         boolean traceSignal) {
        MediaType responseContentType = httpContentCodec.resolveResponseContentType(
                requestContentType, acceptTypes);
        byte[] responseBody = emptySignalResponseBody(responseContentType, traceSignal);
        return ResponseEntity.ok()
                .contentType(responseContentType)
                .body(responseBody);
    }

    private byte[] emptySignalResponseBody(MediaType responseContentType, boolean traceSignal) {
        byte[] protobufBody = traceSignal
                ? ExportTraceServiceResponse.getDefaultInstance().toByteArray()
                : ExportMetricsServiceResponse.getDefaultInstance().toByteArray();
        if (responseContentType != null && MediaType.APPLICATION_JSON.includes(responseContentType)) {
            return httpContentCodec.responseBodyForClient(protobufBody, signal(traceSignal), responseContentType);
        }
        return protobufBody;
    }

    private OtlpHttpContentCodec.Signal signal(boolean traceSignal) {
        return traceSignal ? OtlpHttpContentCodec.Signal.TRACES : OtlpHttpContentCodec.Signal.METRICS;
    }

    private ExportMetricsServiceRequest normalizeAndEnrichMetricRequest(ExportMetricsServiceRequest request,
                                                                        OtlpCorrelationContext correlationContext) {
        OtlpCorrelationContext safeContext = correlationContext == null
                ? OtlpCorrelationContext.empty()
                : correlationContext;
        ExportMetricsServiceRequest resolved =
                otlpEntityIdentityResolver.enrichMetrics(request, safeContext.workspaceId());
        return protobufRedactor.redactMetrics(otlpCorrelationEnricher.enrichMetrics(resolved, safeContext));
    }

    private ExportTraceServiceRequest normalizeAndEnrichTraceRequest(ExportTraceServiceRequest request,
                                                                     OtlpCorrelationContext correlationContext) {
        OtlpCorrelationContext safeContext = correlationContext == null
                ? OtlpCorrelationContext.empty()
                : correlationContext;
        ExportTraceServiceRequest normalized = traceRequestNormalizer.normalize(request);
        ExportTraceServiceRequest resolved =
                otlpEntityIdentityResolver.enrichTraces(normalized, safeContext.workspaceId());
        return protobufRedactor.redactTraces(otlpCorrelationEnricher.enrichTraces(resolved, safeContext));
    }

    private String defaultErrorMessage(Exception ex) {
        return StringUtils.defaultIfBlank(ex.getMessage(), "OTLP signal ingestion failed.");
    }

    private long requestBytes(byte[] content) {
        return content == null ? 0L : content.length;
    }

    private long requestBytes(Message request) {
        return request == null ? 0L : request.getSerializedSize();
    }

    private long durationMillis(long startedAtNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    private List<MetricObservation> extractMetricObservations(Metric metric) {
        if (metric == null) {
            return List.of();
        }
        if (metric.hasGauge()) {
            List<MetricObservation> observations = new ArrayList<>(metric.getGauge().getDataPointsCount());
            for (NumberDataPoint dataPoint : metric.getGauge().getDataPointsList()) {
                observations.add(fromNumberDataPoint("gauge", dataPoint, metric.getGauge().getDataPointsCount(), null, null));
            }
            return observations;
        }
        if (metric.hasSum()) {
            List<MetricObservation> observations = new ArrayList<>(metric.getSum().getDataPointsCount());
            for (NumberDataPoint dataPoint : metric.getSum().getDataPointsList()) {
                observations.add(fromNumberDataPoint(
                        "sum",
                        dataPoint,
                        metric.getSum().getDataPointsCount(),
                        metric.getSum().getAggregationTemporality().name(),
                        String.valueOf(metric.getSum().getIsMonotonic())
                ));
            }
            return observations;
        }
        if (metric.hasHistogram()) {
            List<MetricObservation> observations = new ArrayList<>(metric.getHistogram().getDataPointsCount());
            for (HistogramDataPoint dataPoint : metric.getHistogram().getDataPointsList()) {
                observations.add(fromHistogramDataPoint(
                        "histogram",
                        dataPoint,
                        metric.getHistogram().getDataPointsCount(),
                        metric.getHistogram().getAggregationTemporality().name()
                ));
            }
            return observations;
        }
        if (metric.hasExponentialHistogram()) {
            List<MetricObservation> observations = new ArrayList<>(metric.getExponentialHistogram().getDataPointsCount());
            for (ExponentialHistogramDataPoint dataPoint : metric.getExponentialHistogram().getDataPointsList()) {
                observations.add(fromExponentialHistogramDataPoint(
                        "exponential_histogram",
                        dataPoint,
                        metric.getExponentialHistogram().getDataPointsCount(),
                        metric.getExponentialHistogram().getAggregationTemporality().name()
                ));
            }
            return observations;
        }
        if (metric.hasSummary()) {
            List<MetricObservation> observations = new ArrayList<>(metric.getSummary().getDataPointsCount());
            for (SummaryDataPoint dataPoint : metric.getSummary().getDataPointsList()) {
                observations.add(fromSummaryDataPoint("summary", dataPoint, metric.getSummary().getDataPointsCount()));
            }
            return observations;
        }
        return List.of(new MetricObservation("metric", null, null, baseMetricMetadata("unsupported", "unsupported",
                "unsupported", OtlpIngestionMessages.get("observability.otlp.metric.compatibility.unknown-type"))));
    }

    private MetricObservation fromNumberDataPoint(String metricType,
                                                  NumberDataPoint point,
                                                  int dataPointCount,
                                                  String aggregationTemporality,
                                                  String monotonic) {
        Double value = switch (point.getValueCase()) {
            case AS_DOUBLE -> point.getAsDouble();
            case AS_INT -> (double) point.getAsInt();
            case VALUE_NOT_SET -> null;
        };
        Map<String, String> metadata = baseMetricMetadata("supported", "supported", "supported", null);
        appendMetricTimeRange(metadata, point.getStartTimeUnixNano(), point.getTimeUnixNano());
        metadata.put(OTLP_METRIC_DATA_POINT_COUNT, String.valueOf(dataPointCount));
        putIfHasText(metadata, OTLP_METRIC_AGGREGATION_TEMPORALITY, aggregationTemporality);
        putIfHasText(metadata, OTLP_METRIC_MONOTONIC, monotonic);
        return new MetricObservation(metricType, nanosToMillis(point.getTimeUnixNano()), value,
                mergeMetricAttributes(toStringMap(point.getAttributesList()), metadata));
    }

    private MetricObservation fromHistogramDataPoint(String metricType,
                                                     HistogramDataPoint point,
                                                     int dataPointCount,
                                                     String aggregationTemporality) {
        Double value = point.hasSum() ? point.getSum() : (double) point.getCount();
        Map<String, String> metadata = baseMetricMetadata(
                "partial",
                "supported",
                "partial",
                OtlpIngestionMessages.get("observability.otlp.metric.compatibility.histogram.reason")
        );
        appendMetricTimeRange(metadata, point.getStartTimeUnixNano(), point.getTimeUnixNano());
        metadata.put(OTLP_METRIC_DATA_POINT_COUNT, String.valueOf(dataPointCount));
        putIfHasText(metadata, OTLP_METRIC_AGGREGATION_TEMPORALITY, aggregationTemporality);
        metadata.put(OTLP_METRIC_HISTOGRAM_COUNT, String.valueOf(point.getCount()));
        if (point.hasSum()) {
            metadata.put(OTLP_METRIC_HISTOGRAM_SUM, String.valueOf(point.getSum()));
        }
        if (!point.getExplicitBoundsList().isEmpty()) {
            metadata.put(OTLP_METRIC_HISTOGRAM_EXPLICIT_BOUNDS, toJson(point.getExplicitBoundsList()));
        }
        if (!point.getBucketCountsList().isEmpty()) {
            metadata.put(OTLP_METRIC_HISTOGRAM_BUCKET_COUNTS, toJson(point.getBucketCountsList()));
        }
        return new MetricObservation(metricType, nanosToMillis(point.getTimeUnixNano()), value,
                mergeMetricAttributes(toStringMap(point.getAttributesList()), metadata));
    }

    private MetricObservation fromExponentialHistogramDataPoint(String metricType,
                                                                ExponentialHistogramDataPoint point,
                                                                int dataPointCount,
                                                                String aggregationTemporality) {
        Double value = point.hasSum() ? point.getSum() : (double) point.getCount();
        Map<String, String> metadata = baseMetricMetadata(
                "unsupported",
                "unsupported",
                "partial",
                OtlpIngestionMessages.get("observability.otlp.metric.compatibility.exponential-histogram.reason")
        );
        appendMetricTimeRange(metadata, point.getStartTimeUnixNano(), point.getTimeUnixNano());
        metadata.put(OTLP_METRIC_DATA_POINT_COUNT, String.valueOf(dataPointCount));
        putIfHasText(metadata, OTLP_METRIC_AGGREGATION_TEMPORALITY, aggregationTemporality);
        metadata.put(OTLP_METRIC_EXP_SCALE, String.valueOf(point.getScale()));
        metadata.put(OTLP_METRIC_EXP_ZERO_COUNT, String.valueOf(point.getZeroCount()));
        metadata.put(OTLP_METRIC_EXP_ZERO_THRESHOLD, String.valueOf(point.getZeroThreshold()));
        if (point.hasSum()) {
            metadata.put(OTLP_METRIC_HISTOGRAM_SUM, String.valueOf(point.getSum()));
        }
        if (point.hasPositive()) {
            metadata.put(OTLP_METRIC_EXP_POSITIVE, toJson(Map.of(
                    "offset", point.getPositive().getOffset(),
                    "bucketCounts", point.getPositive().getBucketCountsList()
            )));
        }
        if (point.hasNegative()) {
            metadata.put(OTLP_METRIC_EXP_NEGATIVE, toJson(Map.of(
                    "offset", point.getNegative().getOffset(),
                    "bucketCounts", point.getNegative().getBucketCountsList()
            )));
        }
        return new MetricObservation(metricType, nanosToMillis(point.getTimeUnixNano()), value,
                mergeMetricAttributes(toStringMap(point.getAttributesList()), metadata));
    }

    private MetricObservation fromSummaryDataPoint(String metricType, SummaryDataPoint point, int dataPointCount) {
        Map<String, String> metadata = baseMetricMetadata(
                "partial",
                "partial",
                "partial",
                OtlpIngestionMessages.get("observability.otlp.metric.compatibility.summary.reason")
        );
        appendMetricTimeRange(metadata, point.getStartTimeUnixNano(), point.getTimeUnixNano());
        metadata.put(OTLP_METRIC_DATA_POINT_COUNT, String.valueOf(dataPointCount));
        metadata.put(OTLP_METRIC_SUMMARY_COUNT, String.valueOf(point.getCount()));
        metadata.put(OTLP_METRIC_SUMMARY_SUM, String.valueOf(point.getSum()));
        if (!point.getQuantileValuesList().isEmpty()) {
            List<Map<String, Double>> quantiles = new ArrayList<>(point.getQuantileValuesCount());
            for (SummaryDataPoint.ValueAtQuantile valueAtQuantile : point.getQuantileValuesList()) {
                quantiles.add(Map.of(
                        "quantile", valueAtQuantile.getQuantile(),
                        "value", valueAtQuantile.getValue()
                ));
            }
            metadata.put(OTLP_METRIC_SUMMARY_QUANTILES, toJson(quantiles));
        }
        return new MetricObservation(metricType, nanosToMillis(point.getTimeUnixNano()), point.getSum(),
                mergeMetricAttributes(toStringMap(point.getAttributesList()), metadata));
    }

    private Long nanosToMillis(long value) {
        return value <= 0 ? null : value / 1_000_000L;
    }

    private Map<String, String> toStringMap(List<KeyValue> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (KeyValue attribute : attributes) {
            if (attribute == null || !StringUtils.isNotBlank(attribute.getKey())) {
                continue;
            }
            String value = anyValueToString(attribute.getValue());
            if (StringUtils.isNotBlank(value)) {
                values.put(attribute.getKey(), value);
            }
        }
        return values;
    }

    private String anyValueToString(AnyValue value) {
        if (value == null) {
            return null;
        }
        return switch (value.getValueCase()) {
            case STRING_VALUE -> StringUtils.trimToNull(value.getStringValue());
            case BOOL_VALUE -> String.valueOf(value.getBoolValue());
            case INT_VALUE -> String.valueOf(value.getIntValue());
            case DOUBLE_VALUE -> String.valueOf(value.getDoubleValue());
            case BYTES_VALUE -> HexFormat.of().formatHex(value.getBytesValue().toByteArray());
            default -> null;
        };
    }

    private Map<String, String> mergeMetricAttributes(Map<String, String> pointAttributes, Map<String, String> metadata) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (pointAttributes != null && !pointAttributes.isEmpty()) {
            merged.putAll(pointAttributes);
        }
        if (metadata != null && !metadata.isEmpty()) {
            merged.putAll(metadata);
        }
        return merged;
    }

    private Map<String, String> baseMetricMetadata(String compatibility,
                                                   String greptimeCompatibility,
                                                   String facadeCompatibility,
                                                   String overallReason) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(OTLP_METRIC_COMPATIBILITY, compatibility);
        metadata.put(OTLP_METRIC_GREPTIME_COMPATIBILITY, greptimeCompatibility);
        metadata.put(OTLP_METRIC_FACADE_COMPATIBILITY, facadeCompatibility);
        if (StringUtils.isNotBlank(overallReason)) {
            metadata.put(OTLP_METRIC_COMPATIBILITY_REASON, overallReason);
        }
        if ("supported".equals(greptimeCompatibility)) {
            metadata.put(OTLP_METRIC_GREPTIME_REASON,
                    OtlpIngestionMessages.get("observability.otlp.metric.greptime.reason.supported"));
        } else if ("partial".equals(greptimeCompatibility)) {
            metadata.put(OTLP_METRIC_GREPTIME_REASON,
                    OtlpIngestionMessages.get("observability.otlp.metric.greptime.reason.partial"));
        } else {
            metadata.put(OTLP_METRIC_GREPTIME_REASON,
                    OtlpIngestionMessages.get("observability.otlp.metric.greptime.reason.unsupported"));
        }
        if ("supported".equals(facadeCompatibility)) {
            metadata.put(OTLP_METRIC_FACADE_REASON,
                    OtlpIngestionMessages.get("observability.otlp.metric.facade.reason.supported"));
        } else if ("partial".equals(facadeCompatibility)) {
            metadata.put(OTLP_METRIC_FACADE_REASON,
                    OtlpIngestionMessages.get("observability.otlp.metric.facade.reason.partial"));
        } else {
            metadata.put(OTLP_METRIC_FACADE_REASON,
                    OtlpIngestionMessages.get("observability.otlp.metric.facade.reason.unsupported"));
        }
        return metadata;
    }

    private void appendMetricTimeRange(Map<String, String> metadata, long startTimeUnixNano, long endTimeUnixNano) {
        Long startTime = nanosToMillis(startTimeUnixNano);
        Long endTime = nanosToMillis(endTimeUnixNano);
        if (startTime != null) {
            metadata.put(OTLP_METRIC_START_TIME_MILLIS, String.valueOf(startTime));
        }
        if (endTime != null) {
            metadata.put(OTLP_METRIC_END_TIME_MILLIS, String.valueOf(endTime));
        }
    }

    private void putIfHasText(Map<String, String> target, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value);
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private record MetricObservation(String metricType, Long observedAt, Double value, Map<String, String> attributes) {
    }

}
