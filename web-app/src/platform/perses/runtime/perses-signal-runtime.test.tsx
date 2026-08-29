/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { cleanup, render } from '@testing-library/react';
import type { PanelDefinition, QueryDefinition } from '@perses-dev/spec';
import { afterEach, describe, expect, it, vi } from 'vitest';

const runtimeContract = vi.hoisted(() => ({
  panels: [] as PanelDefinition[],
  queries: [] as QueryDefinition[][],
  pluginLoaders: [] as unknown[]
}));

vi.mock('@perses-dev/dashboards', () => ({
  Panel: ({ definition }: { definition: PanelDefinition }) => {
    runtimeContract.panels.push(definition);
    return <div data-testid="perses-panel" />;
  }
}));
vi.mock('@perses-dev/plugin-system', () => ({
  DataQueriesProvider: ({
    children,
    definitions
  }: {
    children: import('react').ReactNode;
    definitions: QueryDefinition[];
  }) => {
    runtimeContract.queries.push(definitions);
    return children;
  }
}));
vi.mock('./perses-runtime-providers', () => ({
  PersesRuntimeProviders: ({
    children,
    pluginLoader
  }: {
    children: import('react').ReactNode;
    pluginLoader: unknown;
  }) => {
    runtimeContract.pluginLoaders.push(pluginLoader);
    return children;
  }
}));
vi.mock('../plugins/perses-multi-signal-plugin-loader', () => ({
  hertzBeatPersesMultiSignalPluginLoader: { kind: 'multi-signal-loader' }
}));

import { hertzBeatPersesMultiSignalPluginLoader } from '../plugins/perses-multi-signal-plugin-loader';
import { PersesSignalRuntime, type PersesSignalRuntimeProps } from './perses-signal-runtime';

const timeWindow = { from: 1_750_000_000_000, to: 1_750_000_060_000 } as const;

describe('PersesSignalRuntime', () => {
  afterEach(() => {
    cleanup();
    runtimeContract.panels = [];
    runtimeContract.queries = [];
    runtimeContract.pluginLoaders = [];
  });

  it('routes each typed snapshot to the matching official Perses panel and query kind', () => {
    const cases: Array<{
      props: PersesSignalRuntimeProps;
      panelKind: string;
      queryKind: string;
      snapshotKind: string;
    }> = [
      {
        props: {
          kind: 'metric-time-series',
          title: 'Metric',
          timeWindow,
          data: {
            timeRange: { start: new Date(timeWindow.from), end: new Date(timeWindow.to) },
            stepMs: 15_000,
            series: []
          }
        },
        panelKind: 'TimeSeriesChart',
        queryKind: 'TimeSeriesQuery',
        snapshotKind: 'HertzBeatSnapshotTimeSeriesQuery'
      },
      {
        props: { kind: 'logs-table', title: 'Logs', timeWindow, data: { entries: [] } },
        panelKind: 'LogsTable',
        queryKind: 'LogQuery',
        snapshotKind: 'HertzBeatSnapshotLogQuery'
      },
      {
        props: { kind: 'trace-table', title: 'Traces', timeWindow, data: { searchResult: [] } },
        panelKind: 'TraceTable',
        queryKind: 'TraceQuery',
        snapshotKind: 'HertzBeatSnapshotTraceQuery'
      },
      {
        props: {
          kind: 'tracing-gantt-chart',
          title: 'Trace detail',
          timeWindow,
          selectedSpanId: '0123456789abcdef',
          data: { trace: { resourceSpans: [] } }
        },
        panelKind: 'TracingGanttChart',
        queryKind: 'TraceQuery',
        snapshotKind: 'HertzBeatSnapshotTraceQuery'
      }
    ];

    for (const item of cases) {
      const view = render(<PersesSignalRuntime {...item.props} />);
      const panel = runtimeContract.panels.at(-1);
      const query = runtimeContract.queries.at(-1)?.[0];
      expect(panel?.spec.plugin.kind).toBe(item.panelKind);
      expect(query?.kind).toBe(item.queryKind);
      expect(query?.spec.plugin.kind).toBe(item.snapshotKind);
      expect(runtimeContract.pluginLoaders.at(-1)).toBe(hertzBeatPersesMultiSignalPluginLoader);
      if (item.props.kind === 'tracing-gantt-chart') {
        expect(panel?.spec.plugin.spec).toMatchObject({ selectedSpanId: '0123456789abcdef' });
      }
      view.unmount();
    }
  });
});
