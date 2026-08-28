/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const runtimeContract = vi.hoisted(() => ({ datasourceApi: undefined as Record<string, unknown> | undefined }));

vi.mock('@mui/material', () => ({
  ThemeProvider: ({ children }: { children?: import('react').ReactNode }) => children
}));
vi.mock('@perses-dev/components', () => ({
  ChartsProvider: ({ children }: { children?: import('react').ReactNode }) => children,
  SnackbarProvider: ({ children }: { children?: import('react').ReactNode }) => children,
  generateChartsTheme: () => ({}),
  getTheme: () => ({})
}));
vi.mock('@perses-dev/dashboards', () => ({
  DatasourceStoreProvider: ({
    children,
    datasourceApi
  }: {
    children?: import('react').ReactNode;
    datasourceApi: Record<string, unknown>;
  }) => {
    runtimeContract.datasourceApi = datasourceApi;
    return children;
  },
  Panel: () => <span data-testid="perses-panel" />,
  VariableProvider: ({ children }: { children?: import('react').ReactNode }) => children
}));
vi.mock('@perses-dev/plugin-system', () => ({
  DataQueriesProvider: ({ children }: { children?: import('react').ReactNode }) => children,
  PluginRegistry: ({ children }: { children?: import('react').ReactNode }) => children,
  TimeRangeProvider: ({ children }: { children?: import('react').ReactNode }) => children
}));
vi.mock('@/core/runtime-theme-context', () => ({ useRuntimeTheme: () => ({ theme: 'light' }) }));
vi.mock('../plugins/hertzbeat-snapshot-query', () => ({ HERTZBEAT_SNAPSHOT_QUERY_KIND: 'HertzBeatSnapshotQuery' }));
vi.mock('../plugins/perses-plugin-loader', () => ({ hertzBeatPersesPluginLoader: {} }));

import { PersesTimeSeriesRuntime } from './perses-time-series-runtime';

describe('PersesTimeSeriesRuntime', () => {
  afterEach(() => {
    cleanup();
    runtimeContract.datasourceApi = undefined;
  });

  it('marks the actual runtime root ready', () => {
    const { container } = render(
      <PersesTimeSeriesRuntime
        title="CPU"
        series={[{ key: 'cpu', name: 'cpu', labels: {}, points: [{ timestamp: 1_750_000_000_000, value: 42 }] }]}
      />
    );

    const runtime = container.querySelector('[data-perses-runtime-state="ready"]');
    expect(runtime).toBeInTheDocument();
    expect(runtime).not.toHaveAttribute('style');
    expect(runtime?.className).not.toBe('');
    expect(runtime).toContainElement(screen.getByTestId('perses-panel'));
    expect(runtimeContract.datasourceApi).toBeDefined();
    expect(runtimeContract.datasourceApi).not.toHaveProperty('buildProxyUrl');
  });
});
