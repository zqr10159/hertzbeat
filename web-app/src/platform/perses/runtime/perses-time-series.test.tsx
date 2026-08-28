/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { HertzBeatTimeSeries } from './perses-time-series-model';

const series: HertzBeatTimeSeries[] = [
  {
    key: 'cpu',
    name: 'cpu',
    labels: { instance: 'server-1' },
    points: [{ timestamp: 1_750_000_000_000, value: 42 }]
  }
];
let expectedErrorListener: ((event: ErrorEvent) => void) | undefined;

describe('PersesTimeSeries', () => {
  afterEach(() => {
    if (expectedErrorListener) window.removeEventListener('error', expectedErrorListener);
    expectedErrorListener = undefined;
    cleanup();
    vi.doUnmock('./perses-time-series-runtime');
    vi.resetModules();
    vi.restoreAllMocks();
  });

  it('does not report ready while the lazy runtime is suspended', async () => {
    let resolveRuntime: ((runtime: { PersesTimeSeriesRuntime: () => ReactNode }) => void) | undefined;
    vi.doMock(
      './perses-time-series-runtime',
      () =>
        new Promise(resolve => {
          resolveRuntime = resolve;
        })
    );
    const { PersesTimeSeries } = await import('./perses-time-series');

    render(<PersesTimeSeries title="CPU" ariaLabel="CPU trend" series={series} errorFallback="Chart unavailable" />);

    const chart = screen.getByRole('img', { name: 'CPU trend' });
    expect(chart).not.toHaveAttribute('data-perses-runtime-state', 'ready');
    expect(chart.querySelector('[data-perses-runtime-state="loading"]')).toBeInTheDocument();
    await waitFor(() => expect(resolveRuntime).toBeTypeOf('function'));

    await act(async () => {
      resolveRuntime?.({
        PersesTimeSeriesRuntime: () => <span data-perses-runtime-state="ready" />
      });
      await Promise.resolve();
    });

    await waitFor(() => expect(chart.querySelector('[data-perses-runtime-state="ready"]')).toBeInTheDocument());
    expect(chart.querySelector('[data-perses-runtime-state="loading"]')).not.toBeInTheDocument();
  });

  it('renders a non-copy fallback when the lazy runtime fails to load', async () => {
    suppressExpectedWindowError();
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.doMock('./perses-time-series-runtime', () => Promise.reject(new Error('sensitive lazy failure')));
    const { PersesTimeSeries } = await import('./perses-time-series');

    render(<PersesTimeSeries title="CPU" ariaLabel="CPU trend" series={series} errorFallback="Chart unavailable" />);

    const fallback = await screen.findByRole('status', { name: 'CPU trend' });
    expect(fallback).toHaveAttribute('data-perses-runtime-state', 'error');
    expect(fallback).toHaveTextContent('Chart unavailable');
    expect(fallback).not.toHaveTextContent('sensitive lazy failure');
    expect(screen.queryByRole('img', { name: 'CPU trend' })).not.toBeInTheDocument();
  });

  it('renders the same fallback when the loaded runtime throws and retries for new chart input', async () => {
    suppressExpectedWindowError();
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.doMock('./perses-time-series-runtime', () => ({
      PersesTimeSeriesRuntime: ({ series: runtimeSeries }: { series: HertzBeatTimeSeries[] }) => {
        if (runtimeSeries[0]?.key === 'cpu') throw new Error('sensitive runtime failure');
        return <span data-perses-runtime-state="ready" />;
      }
    }));
    const { PersesTimeSeries } = await import('./perses-time-series');

    const view = render(
      <PersesTimeSeries title="CPU" ariaLabel="CPU trend" series={series} errorFallback="Chart unavailable" />
    );

    const fallback = await screen.findByRole('status', { name: 'CPU trend' });
    expect(fallback).toHaveTextContent('Chart unavailable');
    expect(fallback).not.toHaveTextContent('sensitive runtime failure');

    view.rerender(
      <PersesTimeSeries
        title="CPU"
        ariaLabel="CPU trend"
        series={[{ ...series[0]!, key: 'memory' }]}
        errorFallback="Chart unavailable"
      />
    );

    const recoveredChart = await screen.findByRole('img', { name: 'CPU trend' });
    await waitFor(() =>
      expect(recoveredChart.querySelector('[data-perses-runtime-state="ready"]')).toBeInTheDocument()
    );
    expect(screen.queryByRole('status', { name: 'CPU trend' })).not.toBeInTheDocument();
  });
});

function suppressExpectedWindowError() {
  expectedErrorListener = event => event.preventDefault();
  window.addEventListener('error', expectedErrorListener);
}
