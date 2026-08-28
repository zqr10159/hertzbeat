/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { z } from 'zod';

import type { ExactTimeWindow } from '@/shared/query-context';

import { HERTZBEAT_QUERY_LIMITS } from './hertzbeat-query-contract';

const nullableText = z.string().nullable();
const safeInteger = z.number().int().safe();
const nonNegativeInteger = safeInteger.nonnegative();
const nullableNonNegativeInteger = nonNegativeInteger.nullable();
const javaLong = z
  .number()
  .finite()
  .refine(Number.isInteger)
  .refine(value => value >= 0);
const nullableJavaLong = javaLong.nullable();
const OTLP_UINT64_MAX = '18446744073709551615';
const nullableNonNegativeDecimal = z
  .string()
  .regex(/^(0|[1-9]\d*)$/)
  .refine(
    value =>
      value.length < OTLP_UINT64_MAX.length || (value.length === OTLP_UINT64_MAX.length && value <= OTLP_UINT64_MAX)
  )
  .nullable();
const stringMap = z.record(z.string(), z.string());
const nullableStringMap = stringMap.nullable();

type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
const jsonValue: z.ZodType<JsonValue> = z.lazy(() =>
  z.union([z.null(), z.boolean(), z.number().finite(), z.string(), z.array(jsonValue), z.record(z.string(), jsonValue)])
);
const nullableJsonMap = z.record(z.string(), jsonValue).nullable();

const metricField = z.object({
  name: nullableText,
  type: z.enum(['number', 'string', 'time', 'bool']).nullable(),
  unit: nullableText
});
const metricFrame = z.object({
  schema: z
    .object({ fields: z.array(metricField).nullable(), labels: nullableStringMap, meta: nullableStringMap })
    .nullable(),
  data: z.array(z.array(jsonValue)).max(HERTZBEAT_QUERY_LIMITS.metricPointsPerSeries).nullable()
});
const metricConsole = z.object({
  context: z
    .object({
      entityId: nullableJavaLong,
      entityType: nullableText,
      entityName: nullableText,
      serviceName: nullableText,
      serviceNamespace: nullableText,
      environment: nullableText,
      collectorId: nullableText,
      instance: nullableText,
      endpoint: nullableText,
      operationName: nullableText,
      start: nullableNonNegativeInteger,
      end: nullableNonNegativeInteger
    })
    .nullable(),
  query: nullableText,
  datasource: nullableText,
  queryMode: nullableText,
  results: z
    .object({
      refId: nullableText,
      status: safeInteger.nullable(),
      msg: nullableText,
      frames: z.array(metricFrame).max(HERTZBEAT_QUERY_LIMITS.metricSeries).nullable()
    })
    .nullable(),
  stats: z
    .object({
      totalSeries: nonNegativeInteger,
      nonEmptySeries: nonNegativeInteger,
      latestObservedAt: nullableNonNegativeInteger
    })
    .refine(stats => stats.nonEmptySeries <= stats.totalSeries)
    .nullable(),
  emptyStateReason: nullableText,
  errorMessage: nullableText
});

const instrumentationScope = z.object({
  name: nullableText,
  version: nullableText,
  attributes: nullableJsonMap,
  droppedAttributesCount: nullableNonNegativeInteger
});
const logRowSchema = z.object({
  timeUnixNano: nullableJavaLong,
  observedTimeUnixNano: nullableJavaLong,
  severityNumber: nullableNonNegativeInteger,
  severityText: nullableText,
  body: jsonValue,
  attributes: nullableJsonMap,
  droppedAttributesCount: nullableNonNegativeInteger,
  traceId: nullableText,
  spanId: nullableText,
  traceFlags: nullableNonNegativeInteger,
  resource: nullableJsonMap,
  resourceSchemaUrl: nullableText,
  instrumentationScope: instrumentationScope.nullable(),
  scopeSchemaUrl: nullableText
});

const traceSummaryShape = {
  traceId: z.string().min(1),
  rootSpanId: nullableText,
  serviceName: nullableText,
  serviceNamespace: nullableText,
  rootSpanName: nullableText,
  durationNanos: nullableJavaLong,
  status: nullableText,
  startTime: nullableNonNegativeInteger,
  errorSpanCount: nonNegativeInteger,
  resourceAttributes: nullableStringMap
};
const traceServiceStat = z
  .object({
    spanCount: nonNegativeInteger.positive(),
    errorCount: nonNegativeInteger
  })
  .refine(stat => stat.errorCount <= stat.spanCount);
const traceServiceStats = z
  .record(
    z.string().refine(serviceName => serviceName.trim().length > 0),
    traceServiceStat
  )
  .refine(stats => Object.keys(stats).length > 0);
const traceRowShape = {
  ...traceSummaryShape,
  spanCount: nonNegativeInteger.positive(),
  serviceStats: traceServiceStats
};
const traceRowSchema = z.object(traceRowShape).superRefine((row, context) => {
  const stats = Object.values(row.serviceStats);
  const spanTotal = stats.reduce((sum, stat) => sum + stat.spanCount, 0);
  const errorTotal = stats.reduce((sum, stat) => sum + stat.errorCount, 0);
  if (
    !Number.isSafeInteger(spanTotal) ||
    !Number.isSafeInteger(errorTotal) ||
    spanTotal !== row.spanCount ||
    errorTotal !== row.errorSpanCount
  ) {
    context.addIssue({ code: 'custom', message: 'Trace service statistics do not match trace totals' });
  }
});
const traceEvent = z.object({
  timeUnixNano: nullableNonNegativeDecimal,
  name: nullableText,
  attributes: nullableJsonMap,
  droppedAttributesCount: nullableNonNegativeInteger
});
const traceLink = z.object({
  traceId: nullableText,
  spanId: nullableText,
  traceState: nullableText,
  attributes: nullableJsonMap,
  droppedAttributesCount: nullableNonNegativeInteger
});
const codeNavigationHint = z.object({
  repositoryUrl: nullableText,
  provider: nullableText,
  defaultPath: nullableText,
  searchQuery: nullableText,
  label: nullableText
});
const traceSpan = z.object({
  traceId: nullableText,
  spanId: nullableText,
  parentSpanId: nullableText,
  spanName: nullableText,
  serviceName: nullableText,
  status: nullableText,
  spanKind: nullableText,
  statusMessage: nullableText,
  traceState: nullableText,
  scopeName: nullableText,
  scopeVersion: nullableText,
  durationNanos: nullableJavaLong,
  startTime: nullableNonNegativeInteger,
  highlighted: z.boolean(),
  resourceAttributes: nullableStringMap,
  spanAttributes: nullableStringMap,
  events: z.array(traceEvent).nullable(),
  links: z.array(traceLink).nullable(),
  codeNavigationHint: codeNavigationHint.nullable()
});
const traceDetail = z.object({ ...traceSummaryShape, spans: z.array(traceSpan).nullable() });

type HertzBeatMetricSeries = {
  key: string;
  name: string;
  unit?: string | undefined;
  labels: Record<string, string>;
  points: Array<{ timestamp: number; value: number }>;
};
export type HertzBeatMetricData = {
  timeWindow: ExactTimeWindow;
  source: string | null;
  series: HertzBeatMetricSeries[];
};
export type HertzBeatLogRow = z.infer<typeof logRowSchema>;
export type HertzBeatTraceRow = z.infer<typeof traceRowSchema>;
export type HertzBeatTraceDetail = z.infer<typeof traceDetail>;
export type HertzBeatTableData<T> = { rows: T[]; total: number };

export class HertzBeatResponseContractError extends Error {
  constructor() {
    super('Unexpected observability response');
    this.name = 'HertzBeatResponseContractError';
  }
}

export class HertzBeatResponseStateError extends Error {
  constructor(readonly kind: 'invalid_request' | 'unavailable') {
    super('Observability response is not ready');
    this.name = 'HertzBeatResponseStateError';
  }
}

export function parseMetricResponse(value: unknown, window: ExactTimeWindow): HertzBeatMetricData | undefined {
  const parsed = metricConsole.safeParse(value);
  if (!parsed.success) throw new HertzBeatResponseContractError();
  const console = parsed.data;
  requireMetricReadyState(console);
  if (console.context?.start !== window.from || console.context.end !== window.to) {
    throw new HertzBeatResponseContractError();
  }
  const frames = requireMetricFrames(console);
  if (frames.length === 0) return undefined;
  const series = frames.map((frame, index) => metricSeries(frame, index));
  return series.some(item => item.points.length > 0)
    ? { timeWindow: window, source: console.datasource, series }
    : undefined;
}

function requireMetricReadyState(console: z.infer<typeof metricConsole>) {
  if (console.emptyStateReason === 'no_context' || console.emptyStateReason === 'unsupported_query') {
    throw new HertzBeatResponseStateError('invalid_request');
  }
  if (console.errorMessage != null || console.emptyStateReason === 'load_failed') {
    throw new HertzBeatResponseStateError('unavailable');
  }
}

function requireMetricFrames(console: z.infer<typeof metricConsole>) {
  if (!console.results || console.results.status !== 200 || !console.results.frames) {
    throw new HertzBeatResponseStateError('unavailable');
  }
  const frames = console.results.frames;
  const nonEmptyFrames = frames.filter(frame => (frame.data?.length ?? 0) > 0).length;
  if (
    !console.stats ||
    console.stats.totalSeries !== frames.length ||
    console.stats.nonEmptySeries !== nonEmptyFrames
  ) {
    throw new HertzBeatResponseContractError();
  }
  return frames;
}

export function parseLogTable(value: unknown, limit: number): HertzBeatTableData<HertzBeatLogRow> | undefined {
  const result = z
    .object({
      content: z.array(logRowSchema),
      totalElements: nonNegativeInteger,
      pageIndex: z.literal(0),
      pageSize: z.literal(limit)
    })
    .safeParse(value);
  if (
    !result.success ||
    (result.data.content.length === 0 && result.data.totalElements > 0) ||
    result.data.content.length > Math.min(limit, result.data.totalElements)
  ) {
    throw new HertzBeatResponseContractError();
  }
  return result.data.content.length > 0 ? { rows: result.data.content, total: result.data.totalElements } : undefined;
}

export function parseTraceTable(value: unknown, limit: number): HertzBeatTableData<HertzBeatTraceRow> | undefined {
  const result = z
    .object({
      content: z.array(traceRowSchema),
      totalElements: nonNegativeInteger,
      totalPages: nonNegativeInteger,
      number: z.literal(0),
      size: z.literal(limit)
    })
    .safeParse(value);
  if (!result.success || result.data.totalPages !== Math.ceil(result.data.totalElements / limit)) {
    throw new HertzBeatResponseContractError();
  }
  if (
    (result.data.content.length === 0 && result.data.totalElements > 0) ||
    result.data.content.length > Math.min(limit, result.data.totalElements)
  ) {
    throw new HertzBeatResponseContractError();
  }
  return result.data.content.length > 0 ? { rows: result.data.content, total: result.data.totalElements } : undefined;
}

export function parseTraceGantt(value: unknown, traceId: string): HertzBeatTraceDetail | undefined {
  if (value == null) return undefined;
  const result = traceDetail.safeParse(value);
  if (!result.success || result.data.traceId !== traceId) throw new HertzBeatResponseContractError();
  const spanIds = result.data.spans?.map(span => {
    if (!span.spanId || (span.traceId !== null && span.traceId !== traceId)) throw new HertzBeatResponseContractError();
    return span.spanId;
  });
  if (spanIds && new Set(spanIds).size !== spanIds.length) throw new HertzBeatResponseContractError();
  return result.data;
}

function metricSeries(frame: z.infer<typeof metricFrame>, index: number): HertzBeatMetricSeries {
  if (!frame.schema?.fields || !frame.schema.labels || !frame.data) throw new HertzBeatResponseContractError();
  const timeIndex = frame.schema.fields.findIndex(field => field.type === 'time');
  const valueIndex = frame.schema.fields.findIndex(field => field.type === 'number');
  if (timeIndex < 0 || valueIndex < 0) throw new HertzBeatResponseContractError();
  const valueField = frame.schema.fields[valueIndex];
  if (!valueField) throw new HertzBeatResponseContractError();
  const name = frame.schema.labels.__name__ ?? valueField.name ?? `series-${index + 1}`;
  const points = frame.data.map(row => {
    const timestamp = finiteNumber(row[timeIndex]);
    const value = finiteNumber(row[valueIndex]);
    if (timestamp == null || value == null) throw new HertzBeatResponseContractError();
    return { timestamp, value };
  });
  return {
    key: `${name}-${index}`,
    name,
    ...(valueField.unit ? { unit: valueField.unit } : {}),
    labels: frame.schema.labels,
    points
  };
}

function finiteNumber(value: JsonValue | undefined) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : undefined;
  if (typeof value !== 'string' || !value.trim()) return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}
