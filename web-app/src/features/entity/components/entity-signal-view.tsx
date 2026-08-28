/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { Button } from 'antd';
import { useTranslation } from 'react-i18next';

import {
  HertzBeatLogsTableResult,
  HertzBeatMetricTimeSeriesResult,
  HertzBeatTraceTableResult,
  HertzBeatTracingGanttChartResult,
  type HertzBeatPersesPrimitiveMessages,
  type HertzBeatTraceGanttQueryOutcome,
  type HertzBeatTraceTableQueryOutcome
} from '@/platform/perses';

import type { EntityExploreSignal } from '../model/entity-operational-navigation';
import type { EntitySignalViewState } from '../model/entity-signal-view-model';
import { EntitySignalBoundMonitorState } from './entity-signal-bound-monitor-state';
import { EvidenceRail, SignalAvailability, SummaryValue } from './entity-signal-evidence';
import styles from './entity-signal-view.module.css';

type ReadyState = Extract<EntitySignalViewState, { kind: 'ready' }>;

export function EntitySignalView({
  state,
  openSignal,
  openTopology
}: {
  state: EntitySignalViewState;
  openSignal: (signal: EntityExploreSignal) => void;
  openTopology: () => void;
}) {
  const { t } = useTranslation();
  if (state.kind === 'invalid_window') {
    return <div className={styles.compactState}>{t('entity.signals.invalidWindow')}</div>;
  }
  const messages = primitiveMessages(t);
  return (
    <section className={styles.workspace} aria-label={t('entity.signals.title')}>
      <SignalAvailability state={state} />
      <div className={styles.investigationGrid}>
        <div className={styles.signalStack}>
          <MetricsSection state={state} messages={messages} open={() => openSignal('metrics')} />
          <LogsSection state={state} messages={messages} open={() => openSignal('logs')} />
          <TracesSection state={state} messages={messages} open={() => openSignal('traces')} />
          <ContextSections state={state} openTopology={openTopology} />
        </div>
        <EvidenceRail state={state} />
      </div>
    </section>
  );
}

function MetricsSection({ state, messages, open }: SectionProps) {
  const { t } = useTranslation();
  if (state.capabilities.metrics !== 'available' || state.red?.state !== 'ready' || !state.redMetrics) return null;
  const outcomes = state.redMetrics;
  return (
    <SignalSection title={t('entity.signals.sections.metrics')} action={open}>
      <div className={styles.redSummary}>
        <SummaryValue
          label={t('entity.signals.red.requestRate')}
          value={formatRate(state.red.summary.requestRatePerSecond)}
        />
        <SummaryValue label={t('entity.signals.red.errorRate')} value={formatPercent(state.red.summary.errorRate)} />
        <SummaryValue
          label={t('entity.signals.red.latencyP95')}
          value={formatLatency(state.red.summary.latencyP95Ms)}
        />
        <SummaryValue label={t('entity.signals.red.requests')} value={formatNumber(state.red.summary.requestCount)} />
      </div>
      <div className={styles.metricGrid}>
        {outcomes.requestRate.state === 'ready' ? (
          <HertzBeatMetricTimeSeriesResult
            title={t('entity.signals.red.requestRate')}
            ariaLabel={t('entity.signals.aria.requestRate')}
            messages={messages}
            query={{
              signal: 'metrics',
              queryKind: 'time-series',
              timeWindow: state.plan.logsQuery.timeWindow,
              metric: { name: 'request_rate_per_second' }
            }}
            outcome={outcomes.requestRate}
          />
        ) : null}
        {outcomes.errorRate.state === 'ready' ? (
          <HertzBeatMetricTimeSeriesResult
            title={t('entity.signals.red.errorRate')}
            ariaLabel={t('entity.signals.aria.errorRate')}
            messages={messages}
            query={{
              signal: 'metrics',
              queryKind: 'time-series',
              timeWindow: state.plan.logsQuery.timeWindow,
              metric: { name: 'error_rate' }
            }}
            outcome={outcomes.errorRate}
          />
        ) : null}
        {outcomes.latencyP95.state === 'ready' ? (
          <HertzBeatMetricTimeSeriesResult
            title={t('entity.signals.red.latencyP95')}
            ariaLabel={t('entity.signals.aria.latencyP95')}
            messages={messages}
            query={{
              signal: 'metrics',
              queryKind: 'time-series',
              timeWindow: state.plan.logsQuery.timeWindow,
              metric: { name: 'latency_p95_ms' }
            }}
            outcome={outcomes.latencyP95}
          />
        ) : null}
      </div>
    </SignalSection>
  );
}

type SectionProps = { state: ReadyState; messages: HertzBeatPersesPrimitiveMessages; open: () => void };

function LogsSection({ state, messages, open }: SectionProps) {
  const { t } = useTranslation();
  if (state.logs?.state !== 'ready') return null;
  return (
    <SignalSection title={t('entity.signals.sections.logs')} action={open}>
      <HertzBeatLogsTableResult
        title={t('entity.signals.sections.logs')}
        ariaLabel={t('entity.signals.aria.logs')}
        messages={messages}
        query={state.plan.logsQuery}
        outcome={state.logs}
      />
    </SignalSection>
  );
}

function TracesSection({ state, messages, open }: SectionProps) {
  const { t } = useTranslation();
  if (state.traces?.state !== 'ready') return null;
  const query = state.plan.tracesQuery;
  return (
    <SignalSection title={t('entity.signals.sections.traces')} action={open}>
      {query.queryKind === 'gantt' ? (
        <HertzBeatTracingGanttChartResult
          title={t('entity.signals.traceDetail')}
          ariaLabel={t('entity.signals.aria.traceDetail')}
          messages={messages}
          query={query}
          outcome={state.traces as Extract<HertzBeatTraceGanttQueryOutcome, { state: 'ready' }>}
        />
      ) : (
        <HertzBeatTraceTableResult
          title={t('entity.signals.sections.traces')}
          ariaLabel={t('entity.signals.aria.traces')}
          messages={messages}
          query={query}
          outcome={state.traces as Extract<HertzBeatTraceTableQueryOutcome, { state: 'ready' }>}
        />
      )}
    </SignalSection>
  );
}

function ContextSections({ state, openTopology }: { state: ReadyState; openTopology: () => void }) {
  const { t } = useTranslation();
  return (
    <div className={styles.contextGrid}>
      {state.capabilities.topology === 'available' && state.topology.total != null ? (
        <SignalSection title={t('entity.signals.sections.topology')} action={openTopology} compact>
          <NameList names={state.topology.names} total={state.topology.total} />
        </SignalSection>
      ) : null}
      <SignalSection title={t('entity.signals.boundMonitors')} compact>
        <EntitySignalBoundMonitorState state={state.boundMonitors} />
      </SignalSection>
    </div>
  );
}

function SignalSection({
  title,
  action,
  compact,
  children
}: {
  title: string;
  action?: (() => void) | undefined;
  compact?: boolean | undefined;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <section aria-label={title} className={`${styles.signalSection} ${compact ? styles.compactSection : ''}`}>
      <header className={styles.sectionHeader}>
        <h2>{title}</h2>
        {action ? <Button onClick={action}>{t('entity.signals.openExplore')}</Button> : null}
      </header>
      {children}
    </section>
  );
}

function NameList({ names, total }: { names: string[]; total: number }) {
  const { t } = useTranslation();
  return (
    <div className={styles.nameList}>
      <strong>{t('entity.signals.items', { count: total })}</strong>
      {names.map(name => (
        <span key={name}>{name}</span>
      ))}
    </div>
  );
}

function primitiveMessages(t: ReturnType<typeof useTranslation>['t']): HertzBeatPersesPrimitiveMessages {
  return {
    loading: t('entity.signals.query.loading'),
    empty: t('entity.signals.query.empty'),
    truncated: t('entity.signals.query.truncated'),
    truncationUnknown: t('entity.signals.query.truncationUnknown'),
    runtimeError: t('entity.signals.query.runtimeError'),
    failures: {
      'perses.query.invalid': t('entity.signals.query.invalid'),
      'perses.query.permission': t('entity.signals.query.permission'),
      'perses.query.overloaded': t('entity.signals.query.overloaded'),
      'perses.query.unavailable': t('entity.signals.query.unavailable'),
      'perses.query.contract': t('entity.signals.query.contract')
    }
  };
}

function formatRate(value: number) {
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(value)}/s`;
}
function formatPercent(value: number) {
  return new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 2 }).format(value);
}
function formatLatency(value: number | null) {
  return value == null ? '—' : `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(value)} ms`;
}
function formatNumber(value: number) {
  return new Intl.NumberFormat().format(value);
}
