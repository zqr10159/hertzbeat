/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { Component, lazy, Suspense, useEffect, useState, type ReactNode } from 'react';

import { queryHertzBeatData } from '../datasource/hertzbeat-query-client';
import type {
  HertzBeatLogTableQuery,
  HertzBeatMetricQuery,
  HertzBeatQueryFailure,
  HertzBeatQueryOutcome,
  HertzBeatTraceGanttQuery,
  HertzBeatTraceTableQuery
} from '../datasource/hertzbeat-query-contract';
import type {
  HertzBeatLogRow,
  HertzBeatMetricData,
  HertzBeatTableData,
  HertzBeatTraceDetail,
  HertzBeatTraceRow
} from '../datasource/hertzbeat-query-schema';
import {
  PersesSignalDataError,
  toPersesLogData,
  toPersesTraceDetailData,
  toPersesTraceSearchData
} from './perses-signal-data';
import { loadPersesRuntime } from './perses-runtime-registry';
import { toPersesTimeSeriesData } from './perses-time-series-model';
import styles from './hertzbeat-perses-primitives.module.css';

type FailureMessageKey = HertzBeatQueryFailure['messageKey'];

export type HertzBeatPersesPrimitiveMessages = {
  loading: ReactNode;
  empty: ReactNode;
  truncated: ReactNode;
  truncationUnknown: ReactNode;
  runtimeError: ReactNode;
  failures: Record<FailureMessageKey, ReactNode>;
};

type SharedPrimitiveProps = {
  title: string;
  ariaLabel: string;
  messages: HertzBeatPersesPrimitiveMessages;
  className?: string | undefined;
};

type PrimitiveState<T> =
  { kind: 'loading'; queryKey: string } | { kind: 'resolved'; queryKey: string; outcome: HertzBeatQueryOutcome<T> };

export type HertzBeatMetricQueryOutcome = HertzBeatQueryOutcome<HertzBeatMetricData>;
export type HertzBeatLogQueryOutcome = HertzBeatQueryOutcome<HertzBeatTableData<HertzBeatLogRow>>;
export type HertzBeatTraceTableQueryOutcome = HertzBeatQueryOutcome<HertzBeatTableData<HertzBeatTraceRow>>;
export type HertzBeatTraceGanttQueryOutcome = HertzBeatQueryOutcome<HertzBeatTraceDetail>;
export type HertzBeatTraceQueryOutcome = HertzBeatTraceTableQueryOutcome | HertzBeatTraceGanttQueryOutcome;

async function loadPersesSignalRuntime() {
  const module = await loadPersesRuntime('multi-signal');
  return { default: module.PersesSignalRuntime };
}

const PersesSignalRuntime = lazy(loadPersesSignalRuntime);

export function HertzBeatMetricTimeSeries(props: SharedPrimitiveProps & { query: HertzBeatMetricQuery }) {
  const state = useHertzBeatQuery(props.query, executeMetricQuery);
  return renderPrimitive(props, state, outcome => ({
    kind: 'metric-time-series' as const,
    title: props.title,
    timeWindow: props.query.timeWindow,
    data: toPersesTimeSeriesData(outcome.data.series, props.query.timeWindow)
  }));
}

export function HertzBeatLogsTable(props: SharedPrimitiveProps & { query: HertzBeatLogTableQuery }) {
  const state = useHertzBeatQuery(props.query, executeLogQuery);
  return renderPrimitive(props, state, outcome => ({
    kind: 'logs-table' as const,
    title: props.title,
    timeWindow: props.query.timeWindow,
    data: toPersesLogData(outcome.data, props.query.timeWindow)
  }));
}

export function HertzBeatTraceTable(props: SharedPrimitiveProps & { query: HertzBeatTraceTableQuery }) {
  const state = useHertzBeatQuery(props.query, executeTraceTableQuery);
  return renderPrimitive(props, state, outcome => ({
    kind: 'trace-table' as const,
    title: props.title,
    timeWindow: props.query.timeWindow,
    data: toPersesTraceSearchData(outcome.data, outcome.truncated === true)
  }));
}

export function HertzBeatTracingGanttChart(props: SharedPrimitiveProps & { query: HertzBeatTraceGanttQuery }) {
  const state = useHertzBeatQuery(props.query, executeTraceGanttQuery);
  return renderPrimitive(props, state, outcome => ({
    kind: 'tracing-gantt-chart' as const,
    title: props.title,
    timeWindow: props.query.timeWindow,
    data: toPersesTraceDetailData(outcome.data),
    selectedSpanId: props.query.spanId
  }));
}

export function HertzBeatMetricTimeSeriesResult(
  props: SharedPrimitiveProps & { query: HertzBeatMetricQuery; outcome: ReadyOutcome<HertzBeatMetricData> }
) {
  return renderPrimitive(props, resolvedState(props.query, props.outcome), outcome => ({
    kind: 'metric-time-series' as const,
    title: props.title,
    timeWindow: props.query.timeWindow,
    data: toPersesTimeSeriesData(outcome.data.series, props.query.timeWindow)
  }));
}

export function HertzBeatLogsTableResult(
  props: SharedPrimitiveProps & {
    query: HertzBeatLogTableQuery;
    outcome: ReadyOutcome<HertzBeatTableData<HertzBeatLogRow>>;
  }
) {
  return renderPrimitive(props, resolvedState(props.query, props.outcome), outcome => ({
    kind: 'logs-table' as const,
    title: props.title,
    timeWindow: props.query.timeWindow,
    data: toPersesLogData(outcome.data, props.query.timeWindow)
  }));
}

export function HertzBeatTraceTableResult(
  props: SharedPrimitiveProps & {
    query: HertzBeatTraceTableQuery;
    outcome: ReadyOutcome<HertzBeatTableData<HertzBeatTraceRow>>;
  }
) {
  return renderPrimitive(props, resolvedState(props.query, props.outcome), outcome => ({
    kind: 'trace-table' as const,
    title: props.title,
    timeWindow: props.query.timeWindow,
    data: toPersesTraceSearchData(outcome.data, outcome.truncated === true)
  }));
}

export function HertzBeatTracingGanttChartResult(
  props: SharedPrimitiveProps & { query: HertzBeatTraceGanttQuery; outcome: ReadyOutcome<HertzBeatTraceDetail> }
) {
  return renderPrimitive(props, resolvedState(props.query, props.outcome), outcome => ({
    kind: 'tracing-gantt-chart' as const,
    title: props.title,
    timeWindow: props.query.timeWindow,
    data: toPersesTraceDetailData(outcome.data),
    selectedSpanId: props.query.spanId
  }));
}

function resolvedState<Query, Data>(query: Query, outcome: ReadyOutcome<Data>): PrimitiveState<Data> {
  return { kind: 'resolved', queryKey: JSON.stringify(query), outcome };
}

function executeMetricQuery(query: HertzBeatMetricQuery, signal: AbortSignal) {
  return queryHertzBeatData(query, { signal });
}

function executeLogQuery(query: HertzBeatLogTableQuery, signal: AbortSignal) {
  return queryHertzBeatData(query, { signal });
}

function executeTraceTableQuery(query: HertzBeatTraceTableQuery, signal: AbortSignal) {
  return queryHertzBeatData(query, { signal });
}

function executeTraceGanttQuery(query: HertzBeatTraceGanttQuery, signal: AbortSignal) {
  return queryHertzBeatData(query, { signal });
}

function useHertzBeatQuery<Query, Data>(
  query: Query,
  execute: (query: Query, signal: AbortSignal) => Promise<HertzBeatQueryOutcome<Data>>
): PrimitiveState<Data> {
  const queryKey = JSON.stringify(query);
  const [state, setState] = useState<PrimitiveState<Data>>({ kind: 'loading', queryKey });
  useEffect(() => {
    const controller = new AbortController();
    const executableQuery = JSON.parse(queryKey) as Query;
    async function resolveQuery() {
      try {
        const outcome = await execute(executableQuery, controller.signal);
        if (!controller.signal.aborted) setState({ kind: 'resolved', queryKey, outcome });
      } catch {
        if (!controller.signal.aborted) {
          setState({
            kind: 'resolved',
            queryKey,
            outcome: {
              state: 'error',
              error: { kind: 'unavailable', messageKey: 'perses.query.unavailable', retryable: true }
            }
          });
        }
      }
    }
    void resolveQuery();
    return () => controller.abort();
  }, [execute, queryKey]);
  return state.queryKey === queryKey ? state : { kind: 'loading', queryKey };
}

type ReadyOutcome<T> = Extract<HertzBeatQueryOutcome<T>, { state: 'ready' }>;
type SignalRuntimeProps = React.ComponentProps<typeof PersesSignalRuntime>;

function renderPrimitive<T>(
  props: SharedPrimitiveProps,
  state: PrimitiveState<T>,
  toRuntimeProps: (outcome: ReadyOutcome<T>) => SignalRuntimeProps
) {
  const className = [styles.primitive, props.className].filter(Boolean).join(' ');
  if (state.kind === 'loading') {
    return (
      <div className={className} role="status" aria-label={props.ariaLabel}>
        <div className={styles.state}>{props.messages.loading}</div>
      </div>
    );
  }
  const { outcome } = state;
  if (outcome.state === 'empty') {
    return (
      <div className={className} role="status" aria-label={props.ariaLabel}>
        <div className={styles.state}>{props.messages.empty}</div>
      </div>
    );
  }
  if (outcome.state === 'error') {
    return (
      <div className={className} role="alert" aria-label={props.ariaLabel}>
        <div className={styles.state}>{props.messages.failures[outcome.error.messageKey]}</div>
      </div>
    );
  }
  let runtimeProps: SignalRuntimeProps;
  try {
    runtimeProps = toRuntimeProps(outcome);
  } catch (error) {
    if (!(error instanceof PersesSignalDataError)) throw error;
    return (
      <div className={className} role="alert" aria-label={props.ariaLabel}>
        <div className={styles.state}>{props.messages.failures['perses.query.contract']}</div>
      </div>
    );
  }
  const runtimeRole = runtimeProps.kind === 'metric-time-series' ? 'img' : 'region';
  return (
    <div className={className} data-visualization-runtime="perses">
      <PersesPrimitiveErrorBoundary
        ariaLabel={props.ariaLabel}
        fallback={props.messages.runtimeError}
        resetKey={state.queryKey}
      >
        <div className={styles.runtime} role={runtimeRole} aria-label={props.ariaLabel}>
          <Suspense fallback={<div className={styles.state}>{props.messages.loading}</div>}>
            <PersesSignalRuntime {...runtimeProps} />
          </Suspense>
        </div>
      </PersesPrimitiveErrorBoundary>
      <Completeness ariaLabel={props.ariaLabel} truncated={outcome.truncated} messages={props.messages} />
    </div>
  );
}

function Completeness({
  ariaLabel,
  truncated,
  messages
}: {
  ariaLabel: string;
  truncated: boolean | 'unknown';
  messages: HertzBeatPersesPrimitiveMessages;
}) {
  if (truncated === false) return null;
  return (
    <div className={styles.completeness} role="status" aria-label={`${ariaLabel} completeness`}>
      {truncated === true ? messages.truncated : messages.truncationUnknown}
    </div>
  );
}

class PersesPrimitiveErrorBoundary extends Component<
  { ariaLabel: string; fallback: ReactNode; resetKey: string; children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidUpdate(previousProps: Readonly<typeof this.props>) {
    if (this.state.failed && previousProps.resetKey !== this.props.resetKey) this.setState({ failed: false });
  }

  render() {
    if (this.state.failed) {
      return (
        <div className={styles.state} role="alert" aria-label={this.props.ariaLabel}>
          {this.props.fallback}
        </div>
      );
    }
    return this.props.children;
  }
}
