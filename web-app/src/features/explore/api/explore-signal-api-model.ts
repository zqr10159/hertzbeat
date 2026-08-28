/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { ApiMessageError } from '@/core/http/api-message';

import type { InvestigationTraceDetail } from '../model/explore-investigation-contract';
import { exploreUsesExactWindow, timeRangeMilliseconds, type TraceExploreQuery } from '../model/explore-query';
import {
  ExploreSignalContractError,
  ExploreSignalMissingError,
  ExploreSignalUnavailableError,
  type TraceDetail
} from '../model/explore-signal-contract';

export function classifyExploreSignalError(
  reason: unknown
): 'missing' | 'permission' | 'transport_error' | 'contract_error' | 'error' {
  if (reason instanceof ExploreSignalMissingError) return 'missing';
  if (reason instanceof ExploreSignalUnavailableError) return 'transport_error';
  if (reason instanceof ExploreSignalContractError) return 'contract_error';
  return reason instanceof ApiMessageError ? classifyApiMessageError(reason) : 'error';
}

function classifyApiMessageError(reason: ApiMessageError) {
  if (reason.status === 404 || (reason.status === 200 && reason.code === 3)) return 'missing';
  if (reason.status === 401 || reason.status === 403) return 'permission';
  if (reason.cause !== undefined || reason.status === undefined || [0, 502, 503, 504].includes(reason.status)) {
    return 'transport_error';
  }
  return 'error';
}

export function traceDetailWindow(query: TraceExploreQuery, now: number) {
  return exploreUsesExactWindow(query)
    ? { from: query.start!, to: query.end! }
    : { from: now - timeRangeMilliseconds(query.timeRange), to: now };
}

export function toExploreTraceDetail(traceId: string, detail: InvestigationTraceDetail): TraceDetail {
  return {
    traceId,
    rootSpanId: detail.rootSpanId,
    serviceName: detail.serviceName,
    serviceNamespace: detail.serviceNamespace,
    rootSpanName: detail.rootSpanName,
    durationNanos: detail.durationNanos,
    status: detail.status,
    startTime: detail.startTime,
    errorSpanCount: detail.errorSpanCount,
    resourceAttributes: detail.resourceAttributes,
    spans: detail.spans.map(span => ({
      traceId,
      spanId: span.spanId,
      parentSpanId: span.parentSpanId,
      spanName: span.spanName,
      serviceName: span.serviceName,
      status: span.status,
      statusMessage: span.statusMessage,
      spanKind: span.spanKind,
      traceState: span.traceState,
      scopeName: span.scopeName,
      scopeVersion: span.scopeVersion,
      durationNanos: span.durationNanos,
      startTime: span.startTime,
      highlighted: span.highlighted,
      resourceAttributes: span.resourceAttributes,
      spanAttributes: span.spanAttributes,
      events: span.events,
      links: span.links,
      codeNavigationHint: span.codeNavigationHint
    }))
  };
}
