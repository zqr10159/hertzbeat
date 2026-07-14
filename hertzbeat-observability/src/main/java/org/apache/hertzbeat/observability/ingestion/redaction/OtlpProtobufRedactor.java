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

package org.apache.hertzbeat.observability.ingestion.redaction;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.Exemplar;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogram;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.metrics.v1.Summary;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Redacts sensitive values from typed OTLP Protobuf requests.
 */
public final class OtlpProtobufRedactor {

    private final OtlpIngestionRedactionService redactionService;

    public OtlpProtobufRedactor(OtlpIngestionRedactionService redactionService) {
        this.redactionService = Objects.requireNonNull(redactionService);
    }

    public ExportMetricsServiceRequest redactMetrics(ExportMetricsServiceRequest request) {
        if (request == null || request.getResourceMetricsCount() == 0) {
            return request;
        }
        ExportMetricsServiceRequest.Builder requestBuilder = request.toBuilder().clearResourceMetrics();
        for (ResourceMetrics resourceMetrics : request.getResourceMetricsList()) {
            ResourceMetrics.Builder resourceBuilder = resourceMetrics.toBuilder().clearScopeMetrics()
                    .setResource(resourceMetrics.getResource().toBuilder().clearAttributes()
                            .addAllAttributes(redactAttributes(resourceMetrics.getResource().getAttributesList()))
                            .build());
            for (ScopeMetrics scopeMetrics : resourceMetrics.getScopeMetricsList()) {
                ScopeMetrics.Builder scopeBuilder = scopeMetrics.toBuilder().clearMetrics()
                        .setScope(redactInstrumentationScope(scopeMetrics.getScope()));
                for (Metric metric : scopeMetrics.getMetricsList()) {
                    scopeBuilder.addMetrics(redactMetric(metric));
                }
                resourceBuilder.addScopeMetrics(scopeBuilder.build());
            }
            requestBuilder.addResourceMetrics(resourceBuilder.build());
        }
        return requestBuilder.build();
    }

    public ExportLogsServiceRequest redactLogs(ExportLogsServiceRequest request) {
        if (request == null || request.getResourceLogsCount() == 0) {
            return request;
        }
        ExportLogsServiceRequest.Builder requestBuilder = request.toBuilder().clearResourceLogs();
        for (ResourceLogs resourceLogs : request.getResourceLogsList()) {
            ResourceLogs.Builder resourceBuilder = resourceLogs.toBuilder().clearScopeLogs()
                    .setResource(resourceLogs.getResource().toBuilder().clearAttributes()
                            .addAllAttributes(redactAttributes(resourceLogs.getResource().getAttributesList()))
                            .build());
            for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
                ScopeLogs.Builder scopeBuilder = scopeLogs.toBuilder().clearLogRecords()
                        .setScope(redactInstrumentationScope(scopeLogs.getScope()));
                for (LogRecord logRecord : scopeLogs.getLogRecordsList()) {
                    scopeBuilder.addLogRecords(redactLogRecord(logRecord));
                }
                resourceBuilder.addScopeLogs(scopeBuilder.build());
            }
            requestBuilder.addResourceLogs(resourceBuilder.build());
        }
        return requestBuilder.build();
    }

    public ExportTraceServiceRequest redactTraces(ExportTraceServiceRequest request) {
        if (request == null || request.getResourceSpansCount() == 0) {
            return request;
        }
        ExportTraceServiceRequest.Builder requestBuilder = request.toBuilder().clearResourceSpans();
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            ResourceSpans.Builder resourceBuilder = resourceSpans.toBuilder().clearScopeSpans()
                    .setResource(resourceSpans.getResource().toBuilder().clearAttributes()
                            .addAllAttributes(redactAttributes(resourceSpans.getResource().getAttributesList()))
                            .build());
            for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
                ScopeSpans.Builder scopeBuilder = scopeSpans.toBuilder().clearSpans()
                        .setScope(redactInstrumentationScope(scopeSpans.getScope()));
                for (Span span : scopeSpans.getSpansList()) {
                    scopeBuilder.addSpans(redactSpan(span));
                }
                resourceBuilder.addScopeSpans(scopeBuilder.build());
            }
            requestBuilder.addResourceSpans(resourceBuilder.build());
        }
        return requestBuilder.build();
    }

    private Metric redactMetric(Metric metric) {
        if (metric == null) {
            return null;
        }
        Metric.Builder metricBuilder = metric.toBuilder();
        if (metric.hasGauge()) {
            Gauge.Builder builder = metric.getGauge().toBuilder().clearDataPoints();
            for (NumberDataPoint dataPoint : metric.getGauge().getDataPointsList()) {
                builder.addDataPoints(redactNumberDataPoint(dataPoint));
            }
            metricBuilder.setGauge(builder.build());
        }
        if (metric.hasSum()) {
            Sum.Builder builder = metric.getSum().toBuilder().clearDataPoints();
            for (NumberDataPoint dataPoint : metric.getSum().getDataPointsList()) {
                builder.addDataPoints(redactNumberDataPoint(dataPoint));
            }
            metricBuilder.setSum(builder.build());
        }
        if (metric.hasHistogram()) {
            Histogram.Builder builder = metric.getHistogram().toBuilder().clearDataPoints();
            for (HistogramDataPoint dataPoint : metric.getHistogram().getDataPointsList()) {
                builder.addDataPoints(redactHistogramDataPoint(dataPoint));
            }
            metricBuilder.setHistogram(builder.build());
        }
        if (metric.hasExponentialHistogram()) {
            ExponentialHistogram.Builder builder = metric.getExponentialHistogram().toBuilder().clearDataPoints();
            for (ExponentialHistogramDataPoint dataPoint : metric.getExponentialHistogram().getDataPointsList()) {
                builder.addDataPoints(redactExponentialHistogramDataPoint(dataPoint));
            }
            metricBuilder.setExponentialHistogram(builder.build());
        }
        if (metric.hasSummary()) {
            Summary.Builder builder = metric.getSummary().toBuilder().clearDataPoints();
            for (SummaryDataPoint dataPoint : metric.getSummary().getDataPointsList()) {
                builder.addDataPoints(redactSummaryDataPoint(dataPoint));
            }
            metricBuilder.setSummary(builder.build());
        }
        return metricBuilder.build();
    }

    private NumberDataPoint redactNumberDataPoint(NumberDataPoint dataPoint) {
        NumberDataPoint.Builder builder = dataPoint.toBuilder()
                .clearAttributes()
                .clearExemplars()
                .addAllAttributes(redactAttributes(dataPoint.getAttributesList()));
        for (Exemplar exemplar : dataPoint.getExemplarsList()) {
            builder.addExemplars(redactExemplar(exemplar));
        }
        return builder.build();
    }

    private HistogramDataPoint redactHistogramDataPoint(HistogramDataPoint dataPoint) {
        HistogramDataPoint.Builder builder = dataPoint.toBuilder()
                .clearAttributes()
                .clearExemplars()
                .addAllAttributes(redactAttributes(dataPoint.getAttributesList()));
        for (Exemplar exemplar : dataPoint.getExemplarsList()) {
            builder.addExemplars(redactExemplar(exemplar));
        }
        return builder.build();
    }

    private ExponentialHistogramDataPoint redactExponentialHistogramDataPoint(
            ExponentialHistogramDataPoint dataPoint) {
        ExponentialHistogramDataPoint.Builder builder = dataPoint.toBuilder()
                .clearAttributes()
                .clearExemplars()
                .addAllAttributes(redactAttributes(dataPoint.getAttributesList()));
        for (Exemplar exemplar : dataPoint.getExemplarsList()) {
            builder.addExemplars(redactExemplar(exemplar));
        }
        return builder.build();
    }

    private SummaryDataPoint redactSummaryDataPoint(SummaryDataPoint dataPoint) {
        return dataPoint.toBuilder().clearAttributes()
                .addAllAttributes(redactAttributes(dataPoint.getAttributesList()))
                .build();
    }

    private Exemplar redactExemplar(Exemplar exemplar) {
        if (exemplar == null) {
            return null;
        }
        return exemplar.toBuilder()
                .clearFilteredAttributes()
                .addAllFilteredAttributes(redactAttributes(exemplar.getFilteredAttributesList()))
                .build();
    }

    private LogRecord redactLogRecord(LogRecord logRecord) {
        if (logRecord == null) {
            return null;
        }
        return logRecord.toBuilder()
                .setBody(redactAnyValue(logRecord.getBody()))
                .clearAttributes()
                .addAllAttributes(redactAttributes(logRecord.getAttributesList()))
                .build();
    }

    private Span redactSpan(Span span) {
        if (span == null) {
            return null;
        }
        Span.Builder spanBuilder = span.toBuilder()
                .setName(redactionService.redactText(span.getName()))
                .clearAttributes()
                .clearEvents()
                .clearLinks();
        spanBuilder.addAllAttributes(redactAttributes(span.getAttributesList()));
        if (span.hasStatus()) {
            spanBuilder.setStatus(redactSpanStatus(span.getStatus()));
        }
        for (Span.Event event : span.getEventsList()) {
            spanBuilder.addEvents(redactSpanEvent(event));
        }
        for (Span.Link link : span.getLinksList()) {
            spanBuilder.addLinks(redactSpanLink(link));
        }
        return spanBuilder.build();
    }

    private io.opentelemetry.proto.trace.v1.Status redactSpanStatus(
            io.opentelemetry.proto.trace.v1.Status status) {
        if (status == null) {
            return null;
        }
        return status.toBuilder()
                .setMessage(redactionService.redactText(status.getMessage()))
                .build();
    }

    private Span.Event redactSpanEvent(Span.Event event) {
        if (event == null) {
            return null;
        }
        return event.toBuilder()
                .setName(redactionService.redactText(event.getName()))
                .clearAttributes()
                .addAllAttributes(redactAttributes(event.getAttributesList()))
                .build();
    }

    private Span.Link redactSpanLink(Span.Link link) {
        if (link == null) {
            return null;
        }
        return link.toBuilder()
                .clearAttributes()
                .addAllAttributes(redactAttributes(link.getAttributesList()))
                .build();
    }

    private InstrumentationScope redactInstrumentationScope(InstrumentationScope scope) {
        if (scope == null || scope.getAttributesCount() == 0) {
            return scope;
        }
        return scope.toBuilder().clearAttributes()
                .addAllAttributes(redactAttributes(scope.getAttributesList()))
                .build();
    }

    private List<KeyValue> redactAttributes(List<KeyValue> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return List.of();
        }
        List<KeyValue> redacted = new ArrayList<>(attributes.size());
        for (KeyValue attribute : attributes) {
            redacted.add(redactAttribute(attribute));
        }
        return redacted;
    }

    private KeyValue redactAttribute(KeyValue attribute) {
        if (attribute == null || !StringUtils.isNotBlank(attribute.getKey())) {
            return attribute;
        }
        if (redactionService.isSensitiveKey(attribute.getKey())) {
            return attribute.toBuilder().setValue(redactedAnyValue()).build();
        }
        if (!attribute.hasValue()) {
            return attribute;
        }
        return attribute.toBuilder().setValue(redactAnyValue(attribute.getValue())).build();
    }

    private AnyValue redactAnyValue(AnyValue value) {
        if (value == null) {
            return null;
        }
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.toBuilder()
                    .setStringValue(redactionService.redactText(value.getStringValue()))
                    .build();
            case ARRAY_VALUE -> value.toBuilder()
                    .setArrayValue(redactArrayValue(value.getArrayValue()))
                    .build();
            case KVLIST_VALUE -> value.toBuilder()
                    .setKvlistValue(redactKeyValueList(value.getKvlistValue()))
                    .build();
            default -> value;
        };
    }

    private ArrayValue redactArrayValue(ArrayValue value) {
        ArrayValue.Builder builder = value.toBuilder().clearValues();
        for (AnyValue item : value.getValuesList()) {
            builder.addValues(redactAnyValue(item));
        }
        return builder.build();
    }

    private KeyValueList redactKeyValueList(KeyValueList value) {
        KeyValueList.Builder builder = value.toBuilder().clearValues();
        for (KeyValue item : value.getValuesList()) {
            builder.addValues(redactAttribute(item));
        }
        return builder.build();
    }

    private AnyValue redactedAnyValue() {
        return AnyValue.newBuilder().setStringValue(OtlpIngestionRedactionService.REDACTED).build();
    }
}
