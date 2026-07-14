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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.Test;

class OtlpProtobufRedactorTest {

    private final OtlpProtobufRedactor redactor =
            new OtlpProtobufRedactor(new OtlpIngestionRedactionService());

    @Test
    void redactsMetricResourcesScopesDataPointsAndExemplars() {
        NumberDataPoint dataPoint = NumberDataPoint.newBuilder()
                .addAttributes(attribute("message", "token=point"))
                .addExemplars(Exemplar.newBuilder()
                        .addFilteredAttributes(attribute("authorization", "Bearer exemplar-secret-value")))
                .build();
        Metric metric = Metric.newBuilder()
                .setGauge(Gauge.newBuilder().addDataPoints(dataPoint))
                .build();
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(attribute("cloud.auth.token", "resource")))
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .setScope(InstrumentationScope.newBuilder()
                                        .addAttributes(attribute("client_secret", "scope")))
                                .addMetrics(metric)))
                .build();

        ExportMetricsServiceRequest redacted = redactor.redactMetrics(request);

        ResourceMetrics resourceMetrics = redacted.getResourceMetrics(0);
        NumberDataPoint redactedDataPoint = resourceMetrics.getScopeMetrics(0).getMetrics(0)
                .getGauge().getDataPoints(0);
        assertEquals("[REDACTED]", resourceMetrics.getResource().getAttributes(0).getValue().getStringValue());
        assertEquals("[REDACTED]", resourceMetrics.getScopeMetrics(0).getScope()
                .getAttributes(0).getValue().getStringValue());
        assertEquals("token=[REDACTED]", redactedDataPoint.getAttributes(0).getValue().getStringValue());
        assertEquals("[REDACTED]", redactedDataPoint.getExemplars(0)
                .getFilteredAttributes(0).getValue().getStringValue());
        assertFalse(redacted.toString().contains("exemplar-secret-value"));
    }

    @Test
    void redactsLogBodiesIncludingNestedListsAndArrays() {
        AnyValue body = AnyValue.newBuilder()
                .setKvlistValue(KeyValueList.newBuilder()
                        .addValues(attribute("password", "body-secret"))
                        .addValues(KeyValue.newBuilder()
                                .setKey("items")
                                .setValue(AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
                                        .addValues(AnyValue.newBuilder().setStringValue("token=nested"))))))
                .build();
        ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(attribute("service.name", "checkout")))
                        .addScopeLogs(ScopeLogs.newBuilder()
                                .addLogRecords(LogRecord.newBuilder()
                                        .setBody(body)
                                        .addAttributes(attribute("cookie", "session")))))
                .build();

        ExportLogsServiceRequest redacted = redactor.redactLogs(request);

        LogRecord logRecord = redacted.getResourceLogs(0).getScopeLogs(0).getLogRecords(0);
        KeyValueList bodyValues = logRecord.getBody().getKvlistValue();
        assertEquals("[REDACTED]", bodyValues.getValues(0).getValue().getStringValue());
        assertEquals("token=[REDACTED]", bodyValues.getValues(1).getValue()
                .getArrayValue().getValues(0).getStringValue());
        assertEquals("[REDACTED]", logRecord.getAttributes(0).getValue().getStringValue());
        assertFalse(redacted.toString().contains("body-secret"));
    }

    @Test
    void redactsTraceNamesStatusesEventsLinksAndAttributes() {
        Span span = Span.newBuilder()
                .setName("GET /callback token=span")
                .setStatus(Status.newBuilder().setMessage("password=status"))
                .addAttributes(attribute("authorization", "Bearer span-attribute"))
                .addEvents(Span.Event.newBuilder()
                        .setName("retry token=event")
                        .addAttributes(attribute("api_key", "event-key")))
                .addLinks(Span.Link.newBuilder()
                        .addAttributes(attribute("client_secret", "link-secret")))
                .build();
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(attribute("cloud.auth.token", "resource")))
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(span)))
                .build();

        ExportTraceServiceRequest redacted = redactor.redactTraces(request);

        ResourceSpans resourceSpans = redacted.getResourceSpans(0);
        Span redactedSpan = resourceSpans.getScopeSpans(0).getSpans(0);
        assertEquals("[REDACTED]", resourceSpans.getResource().getAttributes(0).getValue().getStringValue());
        assertEquals("GET /callback token=[REDACTED]", redactedSpan.getName());
        assertEquals("password=[REDACTED]", redactedSpan.getStatus().getMessage());
        assertEquals("[REDACTED]", redactedSpan.getAttributes(0).getValue().getStringValue());
        assertEquals("retry token=[REDACTED]", redactedSpan.getEvents(0).getName());
        assertEquals("[REDACTED]", redactedSpan.getEvents(0).getAttributes(0).getValue().getStringValue());
        assertEquals("[REDACTED]", redactedSpan.getLinks(0).getAttributes(0).getValue().getStringValue());
        assertFalse(redacted.toString().contains("link-secret"));
    }

    private KeyValue attribute(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }
}
