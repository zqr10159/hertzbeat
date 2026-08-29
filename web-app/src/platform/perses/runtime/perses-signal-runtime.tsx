/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { Panel } from '@perses-dev/dashboards';
import { DataQueriesProvider } from '@perses-dev/plugin-system';
import type { LogData, PanelDefinition, QueryDefinition, TimeSeriesData, TraceData } from '@perses-dev/spec';
import { useMemo } from 'react';

import type { ExactTimeWindow } from '@/shared/query-context';

import { hertzBeatPersesMultiSignalPluginLoader } from '../plugins/perses-multi-signal-plugin-loader';
import {
  HERTZBEAT_SNAPSHOT_LOG_QUERY_KIND,
  HERTZBEAT_SNAPSHOT_QUERY_KIND,
  HERTZBEAT_SNAPSHOT_TRACE_QUERY_KIND
} from '../plugins/hertzbeat-snapshot-query';
import { PersesRuntimeProviders } from './perses-runtime-providers';
import styles from './perses-time-series.module.css';

export type PersesSignalRuntimeProps =
  | {
      kind: 'metric-time-series';
      title: string;
      timeWindow: ExactTimeWindow;
      data: TimeSeriesData;
      onTimeWindowChange?: ((window: ExactTimeWindow) => void) | undefined;
      timeWindowChangeEnabled?: boolean | undefined;
    }
  | { kind: 'logs-table'; title: string; timeWindow: ExactTimeWindow; data: LogData }
  | { kind: 'trace-table'; title: string; timeWindow: ExactTimeWindow; data: TraceData }
  | {
      kind: 'tracing-gantt-chart';
      title: string;
      timeWindow: ExactTimeWindow;
      data: TraceData;
      selectedSpanId?: string | undefined;
    };

export function PersesSignalRuntime(props: PersesSignalRuntimeProps) {
  const definition = useMemo(() => panelDefinition(props), [props]);
  const queries = useMemo(() => queryDefinitions(props), [props]);
  return (
    <div className={styles.frame} data-perses-runtime-state="ready" data-perses-primitive={props.kind}>
      <PersesRuntimeProviders
        key={`${props.timeWindow.from}:${props.timeWindow.to}`}
        timeWindow={props.timeWindow}
        pluginLoader={hertzBeatPersesMultiSignalPluginLoader}
        onTimeWindowChange={props.kind === 'metric-time-series' ? props.onTimeWindowChange : undefined}
        timeWindowChangeEnabled={props.kind !== 'metric-time-series' || props.timeWindowChangeEnabled !== false}
      >
        <DataQueriesProvider definitions={queries}>
          <Panel panelOptions={{ hideHeader: true }} definition={definition} />
        </DataQueriesProvider>
      </PersesRuntimeProviders>
    </div>
  );
}

function queryDefinitions(props: PersesSignalRuntimeProps): QueryDefinition[] {
  if (props.kind === 'metric-time-series') {
    return [
      {
        kind: 'TimeSeriesQuery',
        spec: { plugin: { kind: HERTZBEAT_SNAPSHOT_QUERY_KIND, spec: { data: props.data } } }
      }
    ];
  }
  if (props.kind === 'logs-table') {
    return [
      {
        kind: 'LogQuery',
        spec: { plugin: { kind: HERTZBEAT_SNAPSHOT_LOG_QUERY_KIND, spec: { data: props.data } } }
      }
    ];
  }
  return [
    {
      kind: 'TraceQuery',
      spec: { plugin: { kind: HERTZBEAT_SNAPSHOT_TRACE_QUERY_KIND, spec: { data: props.data } } }
    }
  ];
}

function panelDefinition(props: PersesSignalRuntimeProps): PanelDefinition {
  const display = { name: props.title };
  if (props.kind === 'metric-time-series') {
    return {
      kind: 'Panel',
      spec: {
        display,
        plugin: {
          kind: 'TimeSeriesChart',
          spec: {
            legend: { position: 'bottom', size: 'medium' },
            visual: { lineWidth: 1.75, showPoints: 'auto' }
          }
        }
      }
    };
  }
  if (props.kind === 'logs-table') {
    return {
      kind: 'Panel',
      spec: {
        display,
        plugin: { kind: 'LogsTable', spec: { allowWrap: true, enableDetails: true, showAll: true, showTime: true } }
      }
    };
  }
  if (props.kind === 'trace-table') {
    return {
      kind: 'Panel',
      spec: { display, plugin: { kind: 'TraceTable', spec: { visual: { palette: { mode: 'auto' } } } } }
    };
  }
  return {
    kind: 'Panel',
    spec: {
      display,
      plugin: {
        kind: 'TracingGanttChart',
        spec: {
          visual: { palette: { mode: 'auto' } },
          ...(props.selectedSpanId ? { selectedSpanId: props.selectedSpanId } : {})
        }
      }
    }
  };
}
