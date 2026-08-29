/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { HertzBeatLogsTableResult, type HertzBeatPersesTableInteraction } from '@/platform/perses';
import { usePublishShellInvestigation } from '@/shared/investigation';
import type { ExactTimeWindow } from '@/shared/query-context';

import { ExploreHistoryPagination } from '../components/explore-history-pagination';
import historyStyles from '../components/explore-history-result.module.css';
import { ExploreLogStatistics } from '../components/explore-log-statistics';
import { explorePersesMessages } from '../components/explore-perses-messages';
import { ExploreMessageResult, ExploreResultFrame } from '../components/explore-state-panel';
import { SignalEmptyState, SignalResultFrame } from '../components/signal-result-frame';
import { materializeLogInvestigation } from '../model/explore-agent-handoff';
import { buildLogInvestigationPath, buildTraceInvestigationPath } from '../model/explore-investigation-model';
import { createExploreLogPersesResult } from '../model/explore-perses-result-model';
import { buildExplorePath, logTrendZoomPatch, mergeExploreQuery, type LogExploreQuery } from '../model/explore-model';
import type { LogHistoryEvidence, LogRow } from '../model/explore-signal-contract';
import { logBody, logTimestampMs } from '../model/explore-signal-model';

type Props = {
  data: LogHistoryEvidence['page'];
  statistics: Pick<LogHistoryEvidence, 'overview' | 'trend'>;
  query: LogExploreQuery;
  openPath: (path: string) => void;
  timeWindow: ExactTimeWindow | undefined;
  revision: number;
  evidenceCurrent: boolean;
};

export function ExplorePersesLogPanel(props: Props) {
  const { data, statistics, query, openPath, timeWindow, revision, evidenceCurrent } = props;
  const { t } = useTranslation();
  const evidence = useMemo(() => currentLogEvidence(data, evidenceCurrent), [data, evidenceCurrent]);
  usePublishShellInvestigation(
    useMemo(() => materializeLogInvestigation(query, evidence, timeWindow), [evidence, query, timeWindow])
  );
  if (!timeWindow) return <ExploreMessageResult kind="error" message={t('explore.states.contractError')} />;
  const result = createExploreLogPersesResult(query, data, timeWindow, revision);
  const onTrendTimeWindowChange = evidenceCurrent
    ? (nextWindow: ExactTimeWindow) => openLogTrendZoom(query, timeWindow, nextWindow, openPath)
    : undefined;
  const statisticsView = (
    <ExploreLogStatistics
      statistics={statistics}
      timeWindow={timeWindow}
      runtimeIdentity={result.runtimeIdentity}
      onTimeWindowChange={onTrendTimeWindowChange}
      t={t}
    />
  );
  const resultView = (
    <SignalResultFrame
      title={t('explore.signals.logs')}
      count={data.totalElements}
      meta={logPageProvenance(data, query, timeWindow, t)}
    >
      {data.totalElements === 0 ? (
        <SignalEmptyState title={t('explore.empty.logs')} hint={t('explore.description')} />
      ) : (
        <HertzBeatLogsTableResult
          title={t('explore.signals.logs')}
          ariaLabel={t('explore.perses.logsTable')}
          query={result.query}
          outcome={result.outcome}
          runtimeIdentity={result.runtimeIdentity}
          interactions={logInteractions(data.content, query, timeWindow, evidenceCurrent, openPath, t)}
          messages={explorePersesMessages(t)}
        />
      )}
      <ExploreHistoryPagination page={data} query={query} enabled={evidenceCurrent} openPath={openPath} t={t} />
    </SignalResultFrame>
  );
  if (!evidenceCurrent || data.totalElements === 0) {
    return (
      <ExploreResultFrame>
        {statisticsView}
        {resultView}
      </ExploreResultFrame>
    );
  }
  return (
    <>
      <section className={historyStyles.logRegion} data-explore-log-region="trend">
        {statisticsView}
      </section>
      <section className={historyStyles.logRegion} data-explore-log-region="result">
        {resultView}
      </section>
    </>
  );
}

function openLogTrendZoom(
  query: LogExploreQuery,
  evidenceWindow: ExactTimeWindow,
  requestedWindow: ExactTimeWindow,
  openPath: (path: string) => void
) {
  const patch = logTrendZoomPatch(query, evidenceWindow, requestedWindow);
  if (patch) openPath(buildExplorePath(mergeExploreQuery(query, patch)));
}

function logPageProvenance(
  page: LogHistoryEvidence['page'],
  query: LogExploreQuery,
  window: ExactTimeWindow,
  t: ReturnType<typeof useTranslation>['t']
) {
  return [
    {
      label: t('explore.perses.rowsReturned'),
      value: `${page.content.length.toLocaleString()} / ${page.totalElements.toLocaleString()}`
    },
    {
      label: t('explore.perses.requestedPage'),
      value: `${((query.pageIndex ?? 0) + 1).toLocaleString()} / ${page.totalPages.toLocaleString()}`
    },
    {
      label: t('explore.perses.evidenceWindow'),
      value: `${new Date(window.from).toISOString()} – ${new Date(window.to).toISOString()}`
    }
  ];
}

function currentLogEvidence(data: LogHistoryEvidence['page'], current: boolean) {
  return current
    ? { totalElements: data.totalElements, number: data.number, size: data.size, contentCount: data.content.length }
    : undefined;
}

function logInteractions(
  rows: LogRow[],
  query: LogExploreQuery,
  window: ExactTimeWindow,
  enabled: boolean,
  openPath: (path: string) => void,
  t: ReturnType<typeof useTranslation>['t']
): HertzBeatPersesTableInteraction[] {
  return rows.flatMap((row, index) => {
    const actions = rowActions(row, query, window, enabled, openPath, t);
    if (actions.length === 0) return [];
    return [
      { key: row.logRecordUid ?? `${row.timeUnixNano ?? 'log'}-${index}`, label: logInteractionLabel(row, t), actions }
    ];
  });
}

function rowActions(
  row: LogRow,
  query: LogExploreQuery,
  window: ExactTimeWindow,
  enabled: boolean,
  openPath: (path: string) => void,
  t: ReturnType<typeof useTranslation>['t']
) {
  const actions: HertzBeatPersesTableInteraction['actions'] = [];
  if (row.logRecordUid) {
    actions.push({
      label: t('explore.perses.investigateLogAction'),
      ariaLabel: t('explore.perses.investigateLog', { value: logInteractionLabel(row, t) }),
      disabled: !enabled,
      onAction: () => openPath(buildLogInvestigationPath(query, selectedLog(row), window, browserTimeZone()))
    });
  }
  if (validTraceId(row.traceId)) {
    actions.push({
      label: t('explore.perses.openTraceAction'),
      ariaLabel: t('explore.perses.openTrace', { value: shortId(row.traceId) }),
      disabled: !enabled,
      onAction: () => openPath(buildTraceInvestigationPath(query, selectedTrace(row), window, browserTimeZone()))
    });
  }
  return actions;
}

function selectedLog(row: LogRow) {
  return { logRecordUid: row.logRecordUid!, timeUnixNano: row.timeUnixNano, traceId: row.traceId, spanId: row.spanId };
}

function selectedTrace(row: LogRow) {
  return { traceId: row.traceId!, selectedSpanId: row.spanId, startTime: null, durationNanos: null };
}

function logInteractionLabel(row: LogRow, t: ReturnType<typeof useTranslation>['t']) {
  const timestamp = logTimestampMs(row);
  const time = timestamp ? new Date(timestamp).toLocaleString() : t('explore.perses.notRecorded');
  return `${time} · ${boundedInteractionSummary(logBody(row) ?? t('explore.perses.notRecorded'))}`;
}

function boundedInteractionSummary(value: string) {
  const normalized = value.replace(/\s+/gu, ' ').trim();
  return normalized.length <= 80 ? normalized : `${normalized.slice(0, 79)}…`;
}

function validTraceId(value: string | null): value is string {
  return value != null && /^[0-9a-f]{32}$/u.test(value);
}

function shortId(value: string) {
  return `${value.slice(0, 8)}…${value.slice(-8)}`;
}

function browserTimeZone() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
