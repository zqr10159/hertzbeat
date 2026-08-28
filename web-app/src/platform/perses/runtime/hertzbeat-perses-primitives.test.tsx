/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { queryHertzBeatData } from '../datasource/hertzbeat-query-client';
import type { HertzBeatQueryOutcome } from '../datasource/hertzbeat-query-contract';

const runtimeControl = vi.hoisted(() => ({ fail: false }));
vi.mock('../datasource/hertzbeat-query-client', async importOriginal => {
  const actual = await importOriginal<typeof import('../datasource/hertzbeat-query-client')>();
  return { ...actual, queryHertzBeatData: vi.fn() };
});
vi.mock('./perses-signal-runtime', () => ({
  PersesSignalRuntime: ({ kind }: { kind: string }) => {
    if (runtimeControl.fail) throw new Error('private runtime detail');
    if (kind === 'logs-table' || kind === 'trace-table') {
      return <table aria-label={`official ${kind}`} data-testid={`official-${kind}`} />;
    }
    if (kind === 'tracing-gantt-chart') {
      return <button data-testid={`official-${kind}`}>Inspect span</button>;
    }
    return <div data-testid={`official-${kind}`} />;
  }
}));

import {
  HertzBeatLogsTable,
  HertzBeatMetricTimeSeries,
  HertzBeatTraceTable,
  HertzBeatTracingGanttChart,
  type HertzBeatPersesPrimitiveMessages
} from './hertzbeat-perses-primitives';

const request = vi.mocked(queryHertzBeatData);
const timeWindow = { from: 1_750_000_000_000, to: 1_750_000_060_000 } as const;
const messages: HertzBeatPersesPrimitiveMessages = {
  loading: 'Loading signal',
  empty: 'No signal data',
  truncated: 'Results are truncated',
  truncationUnknown: 'Result completeness is unknown',
  runtimeError: 'Visualization unavailable',
  failures: {
    'perses.query.invalid': 'Invalid query',
    'perses.query.permission': 'Permission denied',
    'perses.query.overloaded': 'Query capacity unavailable',
    'perses.query.unavailable': 'Storage unavailable',
    'perses.query.contract': 'Unexpected response'
  }
};

describe('HertzBeat Perses primitives', () => {
  beforeEach(() => {
    request.mockReset();
    runtimeControl.fail = false;
  });
  afterEach(cleanup);

  it('renders loading, cancels on unmount, and never converts cancellation into an error state', () => {
    let requestSignal: AbortSignal | undefined;
    request.mockImplementation(
      (_query, options) =>
        new Promise((_resolve, reject) => {
          requestSignal = options?.signal;
          options?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {
            once: true
          });
        }) as never
    );

    const view = render(
      <HertzBeatLogsTable
        title="Logs"
        ariaLabel="Logs table"
        query={{ signal: 'logs', queryKind: 'table', timeWindow }}
        messages={messages}
      />
    );

    const loading = screen.getByRole('status', { name: 'Logs table' });
    expect(loading).toHaveTextContent('Loading signal');
    expect(loading).toHaveStyle({ minHeight: '388px', gridTemplateRows: '360px 28px' });
    view.unmount();
    expect(requestSignal?.aborted).toBe(true);
  });

  it('keeps empty and permission failure distinct and localized by the caller', async () => {
    request.mockResolvedValueOnce({ state: 'empty', truncated: false }).mockResolvedValueOnce({
      state: 'error',
      error: { kind: 'permission', messageKey: 'perses.query.permission', retryable: false }
    });

    const view = render(
      <HertzBeatLogsTable
        title="Logs"
        ariaLabel="Logs table"
        query={{ signal: 'logs', queryKind: 'table', timeWindow }}
        messages={messages}
      />
    );
    expect(await screen.findByRole('status', { name: 'Logs table' })).toHaveTextContent('No signal data');

    view.rerender(
      <HertzBeatLogsTable
        title="Logs"
        ariaLabel="Logs table"
        query={{ signal: 'logs', queryKind: 'table', timeWindow, search: 'failed' }}
        messages={messages}
      />
    );
    expect(await screen.findByRole('alert', { name: 'Logs table' })).toHaveTextContent('Permission denied');
    expect(screen.queryByTestId('official-logs-table')).not.toBeInTheDocument();
  });

  it('clears resolved evidence synchronously when the query identity changes', async () => {
    request.mockResolvedValueOnce(metricOutcome() as never).mockImplementationOnce(
      (_query, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {
            once: true
          });
        }) as never
    );

    const view = render(
      <HertzBeatMetricTimeSeries
        title="Metrics"
        ariaLabel="Metric time series"
        query={{ signal: 'metrics', queryKind: 'time-series', timeWindow, metric: { name: 'up' } }}
        messages={messages}
      />
    );
    expect(await screen.findByTestId('official-metric-time-series')).toBeInTheDocument();

    view.rerender(
      <HertzBeatMetricTimeSeries
        title="Metrics"
        ariaLabel="Metric time series"
        query={{ signal: 'metrics', queryKind: 'time-series', timeWindow, metric: { name: 'cpu_usage' } }}
        messages={messages}
      />
    );

    expect(screen.queryByTestId('official-metric-time-series')).not.toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Metric time series' })).toHaveTextContent('Loading signal');
  });

  it('resets a sanitized runtime failure when the query identity changes', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    request.mockResolvedValue(metricOutcome() as never);
    runtimeControl.fail = true;
    const view = render(
      <HertzBeatMetricTimeSeries
        title="Metrics"
        ariaLabel="Metric time series"
        query={{ signal: 'metrics', queryKind: 'time-series', timeWindow, metric: { name: 'up' } }}
        messages={messages}
      />
    );
    expect(await screen.findByRole('alert', { name: 'Metric time series' })).toHaveTextContent(
      'Visualization unavailable'
    );
    expect(screen.queryByText('private runtime detail')).not.toBeInTheDocument();

    runtimeControl.fail = false;
    view.rerender(
      <HertzBeatMetricTimeSeries
        title="Metrics"
        ariaLabel="Metric time series"
        query={{ signal: 'metrics', queryKind: 'time-series', timeWindow, metric: { name: 'cpu_usage' } }}
        messages={messages}
      />
    );
    expect(await screen.findByTestId('official-metric-time-series')).toBeInTheDocument();
    expect(consoleError).toHaveBeenCalled();
    consoleError.mockRestore();
  });

  it('renders every official primitive from M2 typed ready outcomes', async () => {
    request
      .mockResolvedValueOnce(metricOutcome() as never)
      .mockResolvedValueOnce(logOutcome() as never)
      .mockResolvedValueOnce(traceTableOutcome() as never)
      .mockResolvedValueOnce(traceDetailOutcome() as never);

    const view = render(
      <HertzBeatMetricTimeSeries
        title="Metrics"
        ariaLabel="Metric time series"
        query={{ signal: 'metrics', queryKind: 'time-series', timeWindow, metric: { name: 'up' } }}
        messages={messages}
      />
    );
    expect(await screen.findByTestId('official-metric-time-series')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Metric time series' })).toHaveStyle({ height: '360px' });
    expect(view.container.querySelector('[data-visualization-runtime="perses"]')).toHaveStyle({
      minHeight: '388px',
      gridTemplateRows: '360px 28px'
    });
    expect(screen.getByRole('status', { name: 'Metric time series completeness' })).toHaveTextContent(
      'Result completeness is unknown'
    );

    view.rerender(
      <HertzBeatLogsTable
        title="Logs"
        ariaLabel="Logs table"
        query={{ signal: 'logs', queryKind: 'table', timeWindow }}
        messages={messages}
      />
    );
    expect(await screen.findByTestId('official-logs-table')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Logs table' })).toContainElement(
      screen.getByRole('table', { name: 'official logs-table' })
    );
    const completeness = screen.getByRole('status', { name: 'Logs table completeness' });
    expect(completeness).toHaveTextContent('Results are truncated');
    expect(completeness).toHaveStyle({ height: '28px', minHeight: '28px' });

    view.rerender(
      <HertzBeatTraceTable
        title="Traces"
        ariaLabel="Trace table"
        query={{ signal: 'traces', queryKind: 'table', timeWindow }}
        messages={messages}
      />
    );
    expect(await screen.findByTestId('official-trace-table')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Trace table' })).toContainElement(
      screen.getByRole('table', { name: 'official trace-table' })
    );

    view.rerender(
      <HertzBeatTracingGanttChart
        title="Trace"
        ariaLabel="Trace gantt"
        query={{ signal: 'traces', queryKind: 'gantt', timeWindow, traceId: '0123456789abcdef0123456789abcdef' }}
        messages={messages}
      />
    );
    expect(await screen.findByTestId('official-tracing-gantt-chart')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Trace gantt' })).toContainElement(
      screen.getByRole('button', { name: 'Inspect span' })
    );
    expect(request).toHaveBeenCalledTimes(4);
  });
});

function metricOutcome(): HertzBeatQueryOutcome<unknown> {
  return {
    state: 'ready',
    data: {
      timeWindow,
      source: 'Greptime-promql',
      series: [
        { key: 'up-0', name: 'up', labels: { __name__: 'up' }, points: [{ timestamp: timeWindow.from, value: 1 }] }
      ]
    },
    truncated: 'unknown'
  };
}

function logOutcome(): HertzBeatQueryOutcome<unknown> {
  return {
    state: 'ready',
    data: {
      total: 2,
      rows: [
        {
          timeUnixNano: 1_750_000_001_000_000_000,
          observedTimeUnixNano: null,
          severityNumber: 9,
          severityText: 'INFO',
          body: 'checkout ready',
          attributes: {},
          droppedAttributesCount: 0,
          traceId: '0123456789abcdef0123456789abcdef',
          spanId: '0123456789abcdef',
          traceFlags: 1,
          resource: { 'service.name': 'checkout' },
          resourceSchemaUrl: null,
          instrumentationScope: null,
          scopeSchemaUrl: null
        }
      ]
    },
    truncated: true
  };
}

function traceTableOutcome(): HertzBeatQueryOutcome<unknown> {
  return {
    state: 'ready',
    data: {
      total: 1,
      rows: [
        {
          traceId: '0123456789abcdef0123456789abcdef',
          rootSpanId: '0123456789abcdef',
          serviceName: 'checkout',
          serviceNamespace: 'commerce',
          rootSpanName: 'POST /orders',
          durationNanos: 10_000_000,
          status: 'OK',
          startTime: timeWindow.from,
          spanCount: 2,
          errorSpanCount: 0,
          serviceStats: { checkout: { spanCount: 2, errorCount: 0 } },
          resourceAttributes: {}
        }
      ]
    },
    truncated: false
  };
}

function traceDetailOutcome(): HertzBeatQueryOutcome<unknown> {
  return {
    state: 'ready',
    data: {
      traceId: '0123456789abcdef0123456789abcdef',
      rootSpanId: '0123456789abcdef',
      serviceName: 'checkout',
      serviceNamespace: 'commerce',
      rootSpanName: 'POST /orders',
      durationNanos: 10_000_000,
      status: 'OK',
      startTime: timeWindow.from,
      errorSpanCount: 0,
      resourceAttributes: {},
      spans: [
        {
          traceId: '0123456789abcdef0123456789abcdef',
          spanId: '0123456789abcdef',
          parentSpanId: null,
          spanName: 'POST /orders',
          serviceName: 'checkout',
          status: 'OK',
          spanKind: 'SERVER',
          statusMessage: null,
          traceState: null,
          scopeName: 'checkout-http',
          scopeVersion: null,
          durationNanos: 10_000_000,
          startTime: timeWindow.from,
          highlighted: false,
          resourceAttributes: { 'service.name': 'checkout' },
          spanAttributes: {},
          events: [],
          links: [],
          codeNavigationHint: null
        }
      ]
    },
    truncated: false
  };
}
