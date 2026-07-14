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

package org.apache.hertzbeat.observability.ingestion.storage;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;

/**
 * Storage-neutral gateway for canonical OTLP signal requests.
 *
 * <p>The current distribution provides a GreptimeDB implementation. Keeping
 * the application service on this OTLP boundary allows another backend to be
 * introduced without leaking vendor-specific headers or endpoints into the
 * ingestion workflow.</p>
 *
 * <p>Implementations return a non-null canonical OTLP Protobuf response body
 * after the backend accepts the request. Backend failures must be reported as
 * gRPC status exceptions so HTTP and gRPC entry points share the same error
 * semantics.</p>
 */
public interface OtlpSignalStorage {

    byte[] writeMetrics(ExportMetricsServiceRequest request);

    byte[] writeLogs(ExportLogsServiceRequest request);

    byte[] writeTraces(ExportTraceServiceRequest request);
}
