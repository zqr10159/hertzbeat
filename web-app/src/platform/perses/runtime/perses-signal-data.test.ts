/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { describe, expect, it } from 'vitest';

import type {
  HertzBeatLogRow,
  HertzBeatTableData,
  HertzBeatTraceDetail,
  HertzBeatTraceRow
} from '../datasource/hertzbeat-query-schema';
import { toPersesLogData, toPersesTraceDetailData, toPersesTraceSearchData } from './perses-signal-data';

const timeWindow = { from: 1_750_000_000_000, to: 1_750_000_060_000 } as const;

describe('HertzBeat to Perses signal data', () => {
  it('maps bounded log rows without inventing missing evidence', () => {
    const data: HertzBeatTableData<HertzBeatLogRow> = {
      total: 3,
      rows: [
        {
          timeUnixNano: 1_750_000_001_000_000_000,
          observedTimeUnixNano: null,
          severityNumber: 17,
          severityText: 'ERROR',
          body: { message: 'checkout failed', attempt: 2 },
          attributes: { 'http.route': '/orders', nested: { ignored: true } },
          droppedAttributesCount: 0,
          traceId: 'trace-1',
          spanId: 'span-1',
          traceFlags: 1,
          resource: { 'service.name': 'checkout' },
          resourceSchemaUrl: null,
          instrumentationScope: null,
          scopeSchemaUrl: null
        }
      ]
    };

    expect(toPersesLogData(data, timeWindow)).toEqual({
      timeRange: { start: new Date(timeWindow.from), end: new Date(timeWindow.to) },
      entries: [
        {
          timestamp: 1_750_000_001,
          line: '{"message":"checkout failed","attempt":2}',
          labels: {
            'resource.service.name': 'checkout',
            'attribute.http.route': '/orders',
            severity: 'ERROR',
            trace_id: 'trace-1',
            span_id: 'span-1'
          }
        }
      ],
      totalCount: 3,
      hasMore: true,
      direction: 'backward'
    });
    expect(toPersesLogData(data, timeWindow).entries[0]!.timestamp * 1_000).toBe(1_750_000_001_000);
  });

  it('rejects a log row without an observed timestamp instead of fabricating one', () => {
    const row = {
      timeUnixNano: null,
      observedTimeUnixNano: null,
      severityNumber: null,
      severityText: null,
      body: 'missing timestamp',
      attributes: null,
      droppedAttributesCount: null,
      traceId: null,
      spanId: null,
      traceFlags: null,
      resource: null,
      resourceSchemaUrl: null,
      instrumentationScope: null,
      scopeSchemaUrl: null
    } satisfies HertzBeatLogRow;

    expect(() => toPersesLogData({ rows: [row], total: 1 }, timeWindow)).toThrow('Perses signal data');
  });

  it('maps complete per-service trace statistics without attributing totals to the root service', () => {
    const row = {
      traceId: 'trace-1',
      rootSpanId: 'span-1',
      serviceName: 'checkout',
      serviceNamespace: 'commerce',
      rootSpanName: 'POST /orders',
      durationNanos: 12_500_000,
      status: 'ERROR',
      startTime: 1_750_000_001_000,
      spanCount: 4,
      errorSpanCount: 1,
      serviceStats: {
        checkout: { spanCount: 2, errorCount: 0 },
        'cart-db': { spanCount: 2, errorCount: 1 }
      },
      resourceAttributes: { 'service.name': 'checkout' }
    } satisfies HertzBeatTraceRow;

    expect(toPersesTraceSearchData({ rows: [row], total: 2 }, true)).toEqual({
      searchResult: [
        {
          traceId: 'trace-1',
          rootServiceName: 'checkout',
          rootTraceName: 'POST /orders',
          startTimeUnixMs: 1_750_000_001_000,
          durationMs: 12.5,
          serviceStats: {
            checkout: { spanCount: 2, errorCount: 0 },
            'cart-db': { spanCount: 2, errorCount: 1 }
          }
        }
      ],
      metadata: { hasMoreResults: true }
    });
  });

  it.each([{ serviceName: null }, { serviceName: '   ' }, { rootSpanName: null }, { rootSpanName: '   ' }])(
    'rejects trace table rows without a proven root identity',
    override => {
      const row = {
        traceId: 'trace-1',
        rootSpanId: 'span-1',
        serviceName: 'checkout',
        serviceNamespace: 'commerce',
        rootSpanName: 'POST /orders',
        durationNanos: 12_500_000,
        status: 'OK',
        startTime: 1_750_000_001_000,
        spanCount: 1,
        errorSpanCount: 0,
        serviceStats: { checkout: { spanCount: 1, errorCount: 0 } },
        resourceAttributes: null,
        ...override
      } satisfies HertzBeatTraceRow;

      expect(() => toPersesTraceSearchData({ rows: [row], total: 1 }, false)).toThrow('Perses signal data');
    }
  );

  it('maps trace details into the official OTLP trace model for TracingGanttChart', () => {
    const detail = traceDetail();
    const result = toPersesTraceDetailData(detail);

    expect(result.trace?.resourceSpans).toHaveLength(2);
    expect(result.trace?.resourceSpans[0]).toMatchObject({
      resource: {
        attributes: [
          { key: 'service.name', value: { stringValue: 'checkout' } },
          { key: 'service.namespace', value: { stringValue: 'commerce' } }
        ]
      },
      scopeSpans: [
        {
          scope: { name: 'checkout-http', version: '1.0.0' },
          spans: [
            {
              traceId: 'trace-1',
              spanId: 'span-1',
              name: 'POST /orders',
              startTimeUnixNano: '1750000001000000000',
              endTimeUnixNano: '1750000001010000000',
              status: { code: 'STATUS_CODE_OK' }
            }
          ]
        }
      ]
    });
    expect(result.trace?.resourceSpans[1]?.scopeSpans[0]?.spans[0]).toMatchObject({
      spanId: 'span-2',
      parentSpanId: 'span-1',
      status: { code: 'STATUS_CODE_ERROR', message: 'database unavailable' },
      links: [{ traceId: 'trace-2', spanId: 'span-x' }]
    });
  });

  it('preserves epoch nanoseconds above Number.MAX_SAFE_INTEGER as exact OTLP decimal strings', () => {
    const detail = traceDetail();
    detail.spans![1]!.events = [
      {
        timeUnixNano: '1750000001005000123',
        name: 'exception',
        attributes: {},
        droppedAttributesCount: 0
      }
    ];

    expect(
      toPersesTraceDetailData(detail).trace?.resourceSpans[1]?.scopeSpans[0]?.spans[0]?.events?.[0]?.timeUnixNano
    ).toBe('1750000001005000123');
  });

  it('omits JSON null attributes instead of fabricating a string value', () => {
    const detail = traceDetail();
    detail.spans![1]!.events = [
      {
        timeUnixNano: '1000000',
        name: 'test event',
        attributes: { absent: null, present: 'evidence' },
        droppedAttributesCount: 0
      }
    ];

    expect(detail.spans).not.toBeNull();
    const event = toPersesTraceDetailData(detail).trace?.resourceSpans[1]?.scopeSpans[0]?.spans[0]?.events?.[0];
    expect(event?.attributes).toEqual([{ key: 'present', value: { stringValue: 'evidence' } }]);
  });

  it('rejects unsafe integer log attributes instead of stringifying rounded labels', () => {
    const row = {
      timeUnixNano: 1_750_000_001_000_000_000,
      observedTimeUnixNano: null,
      severityNumber: null,
      severityText: null,
      body: 'unsafe evidence',
      attributes: { sequence: 9_007_199_254_740_992 },
      droppedAttributesCount: null,
      traceId: null,
      spanId: null,
      traceFlags: null,
      resource: null,
      resourceSchemaUrl: null,
      instrumentationScope: null,
      scopeSchemaUrl: null
    } satisfies HertzBeatLogRow;

    expect(() => toPersesLogData({ rows: [row], total: 1 }, timeWindow)).toThrow('Perses signal data');
  });

  it('rejects unsafe integers nested in a structured log body instead of displaying rounded evidence', () => {
    const row = logRow({
      body: { request: { sequence: 9_007_199_254_740_992 } }
    });

    expect(() => toPersesLogData({ rows: [row], total: 1 }, timeWindow)).toThrow('Perses signal data');
  });

  it('preserves safe nested structured log bodies', () => {
    const row = logRow({
      body: {
        request: { sequence: 9_007_199_254_740_991, ratio: 1.25 },
        accepted: true,
        result: null
      }
    });

    expect(toPersesLogData({ rows: [row], total: 1 }, timeWindow).entries[0]?.line).toBe(
      '{"request":{"sequence":9007199254740991,"ratio":1.25},"accepted":true,"result":null}'
    );
  });

  it.each(['event', 'link'])('rejects unsafe integer %s attributes instead of fabricating OTLP evidence', kind => {
    const detail = traceDetail();
    if (kind === 'event') {
      detail.spans![1]!.events = [
        {
          timeUnixNano: '1750000001005000123',
          name: 'exception',
          attributes: { sequence: 9_007_199_254_740_992 },
          droppedAttributesCount: 0
        }
      ];
    } else {
      detail.spans![1]!.links = [
        {
          traceId: 'trace-2',
          spanId: 'span-x',
          traceState: null,
          attributes: { sequence: 9_007_199_254_740_992 },
          droppedAttributesCount: 0
        }
      ];
    }

    expect(() => toPersesTraceDetailData(detail)).toThrow('Perses signal data');
  });
});

function logRow(overrides: Partial<HertzBeatLogRow> = {}): HertzBeatLogRow {
  return {
    timeUnixNano: 1_750_000_001_000_000_000,
    observedTimeUnixNano: null,
    severityNumber: null,
    severityText: null,
    body: 'log body',
    attributes: null,
    droppedAttributesCount: null,
    traceId: null,
    spanId: null,
    traceFlags: null,
    resource: null,
    resourceSchemaUrl: null,
    instrumentationScope: null,
    scopeSchemaUrl: null,
    ...overrides
  };
}

function traceDetail(): HertzBeatTraceDetail {
  return {
    traceId: 'trace-1',
    rootSpanId: 'span-1',
    serviceName: 'checkout',
    serviceNamespace: 'commerce',
    rootSpanName: 'POST /orders',
    durationNanos: 10_000_000,
    status: 'OK',
    startTime: 1_750_000_001_000,
    errorSpanCount: 1,
    resourceAttributes: { 'service.name': 'checkout', 'service.namespace': 'commerce' },
    spans: [
      {
        traceId: 'trace-1',
        spanId: 'span-1',
        parentSpanId: null,
        spanName: 'POST /orders',
        serviceName: 'checkout',
        status: 'OK',
        spanKind: 'SERVER',
        statusMessage: null,
        traceState: null,
        scopeName: 'checkout-http',
        scopeVersion: '1.0.0',
        durationNanos: 10_000_000,
        startTime: 1_750_000_001_000,
        highlighted: false,
        resourceAttributes: { 'service.name': 'checkout', 'service.namespace': 'commerce' },
        spanAttributes: { 'http.route': '/orders' },
        events: [],
        links: [],
        codeNavigationHint: null
      },
      {
        traceId: 'trace-1',
        spanId: 'span-2',
        parentSpanId: 'span-1',
        spanName: 'SELECT cart',
        serviceName: 'cart-db',
        status: 'ERROR',
        spanKind: 'CLIENT',
        statusMessage: 'database unavailable',
        traceState: null,
        scopeName: 'jdbc',
        scopeVersion: null,
        durationNanos: 2_000_000,
        startTime: 1_750_000_001_004,
        highlighted: true,
        resourceAttributes: { 'service.name': 'cart-db' },
        spanAttributes: { 'db.system': 'postgresql' },
        events: [],
        links: [
          {
            traceId: 'trace-2',
            spanId: 'span-x',
            traceState: null,
            attributes: {},
            droppedAttributesCount: 0
          }
        ],
        codeNavigationHint: null
      }
    ]
  };
}
