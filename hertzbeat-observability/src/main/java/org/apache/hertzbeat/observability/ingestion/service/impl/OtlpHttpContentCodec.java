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

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;

/**
 * Owns OTLP HTTP media negotiation and typed response encoding.
 */
final class OtlpHttpContentCodec {

    static final MediaType PROTOBUF_MEDIA_TYPE = MediaType.parseMediaType("application/x-protobuf");
    private static final MediaType ALTERNATE_PROTOBUF_MEDIA_TYPE =
            MediaType.parseMediaType("application/protobuf");

    enum Signal {
        METRICS("OTLP metrics response is malformed."),
        LOGS("OTLP logs response is malformed."),
        TRACES("OTLP trace response is malformed.");

        private final String malformedReason;

        Signal(String malformedReason) {
            this.malformedReason = malformedReason;
        }
    }

    MediaType resolveUpstreamContentType(MediaType contentType) {
        if (contentType == null) {
            return PROTOBUF_MEDIA_TYPE;
        }
        if (MediaType.APPLICATION_JSON.includes(contentType) || isExplicitProtobufMediaType(contentType)) {
            return PROTOBUF_MEDIA_TYPE;
        }
        return contentType;
    }

    List<MediaType> resolveUpstreamAcceptTypes(List<MediaType> acceptTypes) {
        if (acceptTypes == null || acceptTypes.isEmpty()) {
            return List.of();
        }
        return acceptTypes.stream()
                .map(this::resolveUpstreamAcceptType)
                .toList();
    }

    MediaType resolveResponseContentType(MediaType requestContentType, List<MediaType> acceptTypes) {
        return prefersJsonResponse(requestContentType, acceptTypes)
                ? MediaType.APPLICATION_JSON
                : PROTOBUF_MEDIA_TYPE;
    }

    boolean prefersJsonResponse(MediaType requestContentType, List<MediaType> acceptTypes) {
        double jsonQuality = negotiatedQuality(acceptTypes, true);
        double protobufQuality = negotiatedQuality(acceptTypes, false);
        if (jsonQuality >= 0 || protobufQuality >= 0) {
            double acceptableJsonQuality = Math.max(jsonQuality, 0.0d);
            double acceptableProtobufQuality = Math.max(protobufQuality, 0.0d);
            if (Double.compare(acceptableJsonQuality, acceptableProtobufQuality) == 0) {
                return acceptableJsonQuality > 0 && isExplicitJsonMediaType(requestContentType);
            }
            return acceptableJsonQuality > acceptableProtobufQuality;
        }
        return isExplicitJsonMediaType(requestContentType);
    }

    byte[] responseBodyForClient(byte[] upstreamBody, Signal signal, MediaType responseContentType) {
        byte[] safeBody = upstreamBody == null ? new byte[0] : upstreamBody;
        Message response = parseResponse(safeBody, signal);
        if (responseContentType == null || !MediaType.APPLICATION_JSON.includes(responseContentType)) {
            return safeBody;
        }
        try {
            return JsonFormat.printer().print(response).getBytes(StandardCharsets.UTF_8);
        } catch (InvalidProtocolBufferException ex) {
            throw malformedResponse(signal, ex);
        }
    }

    void validateResponseBody(byte[] upstreamBody, Signal signal) {
        parseResponse(upstreamBody == null ? new byte[0] : upstreamBody, signal);
    }

    private MediaType resolveUpstreamAcceptType(MediaType acceptType) {
        if (isExplicitProtobufMediaType(acceptType)) {
            return new MediaType(PROTOBUF_MEDIA_TYPE.getType(), PROTOBUF_MEDIA_TYPE.getSubtype(),
                    acceptType.getParameters());
        }
        return acceptType;
    }

    private Message parseResponse(byte[] body, Signal signal) {
        try {
            if (body.length == 0) {
                return defaultResponse(signal);
            }
            return switch (signal) {
                case METRICS -> ExportMetricsServiceResponse.parseFrom(body);
                case LOGS -> ExportLogsServiceResponse.parseFrom(body);
                case TRACES -> ExportTraceServiceResponse.parseFrom(body);
            };
        } catch (InvalidProtocolBufferException ex) {
            throw malformedResponse(signal, ex);
        }
    }

    private Message defaultResponse(Signal signal) {
        return switch (signal) {
            case METRICS -> ExportMetricsServiceResponse.getDefaultInstance();
            case LOGS -> ExportLogsServiceResponse.getDefaultInstance();
            case TRACES -> ExportTraceServiceResponse.getDefaultInstance();
        };
    }

    private io.grpc.StatusRuntimeException malformedResponse(Signal signal,
                                                              InvalidProtocolBufferException cause) {
        return io.grpc.Status.INTERNAL.withDescription(signal.malformedReason)
                .withCause(cause).asRuntimeException();
    }

    private double negotiatedQuality(List<MediaType> acceptTypes, boolean json) {
        if (acceptTypes == null || acceptTypes.isEmpty()) {
            return -1.0d;
        }
        double explicitQuality = acceptTypes.stream()
                .filter(json ? this::isExplicitJsonMediaType : this::isExplicitProtobufMediaType)
                .mapToDouble(MediaType::getQualityValue)
                .max()
                .orElse(-1.0d);
        if (explicitQuality >= 0) {
            return explicitQuality;
        }
        return acceptTypes.stream()
                .filter(json ? this::isJsonWildcardMediaType : this::isProtobufWildcardMediaType)
                .mapToDouble(MediaType::getQualityValue)
                .max()
                .orElse(-1.0d);
    }

    private boolean isJsonWildcardMediaType(MediaType mediaType) {
        return mediaType != null
                && !isExplicitJsonMediaType(mediaType)
                && mediaType.includes(MediaType.APPLICATION_JSON);
    }

    private boolean isProtobufWildcardMediaType(MediaType mediaType) {
        return mediaType != null
                && !isExplicitProtobufMediaType(mediaType)
                && (mediaType.includes(PROTOBUF_MEDIA_TYPE)
                || mediaType.includes(ALTERNATE_PROTOBUF_MEDIA_TYPE));
    }

    private boolean isExplicitJsonMediaType(MediaType mediaType) {
        if (mediaType == null || mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            return false;
        }
        return "application".equalsIgnoreCase(mediaType.getType())
                && ("json".equalsIgnoreCase(mediaType.getSubtype())
                || mediaType.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json"));
    }

    private boolean isExplicitProtobufMediaType(MediaType mediaType) {
        if (mediaType == null || mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            return false;
        }
        return "application".equalsIgnoreCase(mediaType.getType())
                && ("x-protobuf".equalsIgnoreCase(mediaType.getSubtype())
                || "protobuf".equalsIgnoreCase(mediaType.getSubtype()));
    }
}
