/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { ThemeProvider } from '@mui/material';
import { ChartsProvider, generateChartsTheme, getTheme, SnackbarProvider } from '@perses-dev/components';
import type {
  DashboardResource,
  DatasourceApi,
  DatasourceResource,
  GlobalDatasourceResource
} from '@perses-dev/client';
import { DatasourceStoreProvider, Panel, VariableProvider } from '@perses-dev/dashboards';
import {
  DataQueriesProvider,
  PluginRegistry,
  RouterProvider,
  TimeRangeProvider,
  type PluginLoader
} from '@perses-dev/plugin-system';
import type { DurationString, QueryDefinition, TimeRangeValue } from '@perses-dev/spec';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';

import { useRuntimeTheme } from '@/core/runtime-theme-context';
import type { ExactTimeWindow } from '@/shared/query-context';

import { HERTZBEAT_SNAPSHOT_QUERY_KIND } from '../plugins/hertzbeat-snapshot-query';
import { hertzBeatPersesPluginLoader } from '../plugins/perses-plugin-loader';
import { resolvePersesTimeWindow, toPersesTimeSeriesData, type HertzBeatTimeSeries } from './perses-time-series-model';
import styles from './perses-time-series.module.css';

const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false, retry: false, staleTime: Number.POSITIVE_INFINITY } }
});

const dashboard = {
  kind: 'Dashboard',
  metadata: { name: 'hertzbeat-investigation-runtime', project: 'hertzbeat' },
  spec: { duration: '30m', variables: [], panels: {}, layouts: [] }
} as DashboardResource;

const datasourceApi: DatasourceApi = {
  getDatasource: (): Promise<DatasourceResource | undefined> => Promise.resolve(undefined),
  getGlobalDatasource: (): Promise<GlobalDatasourceResource | undefined> => Promise.resolve(undefined),
  listDatasources: (): Promise<DatasourceResource[]> => Promise.resolve([]),
  listGlobalDatasources: (): Promise<GlobalDatasourceResource[]> => Promise.resolve([])
};

export function PersesTimeSeriesRuntime({
  title,
  series,
  timeWindow
}: {
  title: string;
  series: HertzBeatTimeSeries[];
  timeWindow?: ExactTimeWindow | undefined;
}) {
  const resolvedWindow = useMemo(() => resolvePersesTimeWindow(series, timeWindow), [series, timeWindow]);
  return (
    <div className={styles.frame} data-perses-runtime-state="ready">
      <PersesRuntimeProviders key={`${resolvedWindow.from}:${resolvedWindow.to}`} timeWindow={resolvedWindow}>
        <PersesSnapshotPanel title={title} series={series} timeWindow={resolvedWindow} />
      </PersesRuntimeProviders>
    </div>
  );
}

export function PersesRuntimeProviders({
  children,
  timeWindow,
  pluginLoader = hertzBeatPersesPluginLoader
}: {
  children: ReactNode;
  timeWindow: ExactTimeWindow;
  pluginLoader?: PluginLoader | undefined;
}) {
  const { theme } = useRuntimeTheme();
  const [persesTimeRange, setPersesTimeRange] = useState<TimeRangeValue>(() => toTimeRange(timeWindow));
  const [refreshInterval, setRefreshInterval] = useState<DurationString>('0s');
  const muiTheme = useMemo(() => getTheme(theme === 'dark' ? 'dark' : 'light'), [theme]);
  const chartsTheme = useMemo(() => generateChartsTheme(muiTheme, {}), [muiTheme]);

  return (
    <ThemeProvider theme={muiTheme}>
      <ChartsProvider chartsTheme={chartsTheme}>
        <SnackbarProvider anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }} variant="default" content="">
          <PluginRegistry
            pluginLoader={pluginLoader}
            defaultPluginKinds={{ Panel: 'TimeSeriesChart', TimeSeriesQuery: HERTZBEAT_SNAPSHOT_QUERY_KIND }}
          >
            <RouterProvider RouterComponent={undefined} navigate={undefined}>
              <QueryClientProvider client={queryClient}>
                <TimeRangeProvider
                  timeRange={persesTimeRange}
                  refreshInterval={refreshInterval}
                  setTimeRange={setPersesTimeRange}
                  setRefreshInterval={setRefreshInterval}
                >
                  <VariableProvider>
                    <DatasourceStoreProvider dashboardResource={dashboard} datasourceApi={datasourceApi}>
                      {children}
                    </DatasourceStoreProvider>
                  </VariableProvider>
                </TimeRangeProvider>
              </QueryClientProvider>
            </RouterProvider>
          </PluginRegistry>
        </SnackbarProvider>
      </ChartsProvider>
    </ThemeProvider>
  );
}

function PersesSnapshotPanel({
  title,
  series,
  timeWindow
}: {
  title: string;
  series: HertzBeatTimeSeries[];
  timeWindow: ExactTimeWindow;
}) {
  const data = useMemo(() => toPersesTimeSeriesData(series, timeWindow), [series, timeWindow]);
  const definitions = useMemo<QueryDefinition[]>(
    () => [
      {
        kind: 'TimeSeriesQuery',
        spec: { plugin: { kind: HERTZBEAT_SNAPSHOT_QUERY_KIND, spec: { data } } }
      }
    ],
    [data]
  );
  return (
    <DataQueriesProvider definitions={definitions}>
      <Panel
        panelOptions={{ hideHeader: true }}
        definition={{
          kind: 'Panel',
          spec: {
            display: { name: title },
            plugin: {
              kind: 'TimeSeriesChart',
              spec: {
                legend: { position: 'bottom', size: 'medium' },
                visual: { lineWidth: 1.75, showPoints: 'auto' }
              }
            }
          }
        }}
      />
    </DataQueriesProvider>
  );
}

function toTimeRange(window: ExactTimeWindow): TimeRangeValue {
  return { start: new Date(window.from), end: new Date(window.to) };
}
