/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { Alert } from 'antd';
import type { TFunction } from 'i18next';

import { HertzBeatMetricTimeSeriesResult } from '@/platform/perses';
import type { ExactTimeWindow } from '@/shared/query-context';

import { createLogTrendPersesResult, exploreOverviewRows } from '../model/explore-perses-result-model';
import type { LogHistoryEvidence } from '../model/explore-signal-contract';
import { explorePersesMessages } from './explore-perses-messages';
import styles from './log-result.module.css';

export function ExploreLogStatistics({
  statistics,
  timeWindow,
  runtimeIdentity,
  t
}: {
  statistics: Pick<LogHistoryEvidence, 'overview' | 'trend'>;
  timeWindow: ExactTimeWindow;
  runtimeIdentity: string;
  t: TFunction;
}) {
  return (
    <div className={styles.statistics}>
      <Overview statistics={statistics} t={t} />
      <Trend statistics={statistics} timeWindow={timeWindow} runtimeIdentity={runtimeIdentity} t={t} />
    </div>
  );
}

function Overview({ statistics, t }: { statistics: Pick<LogHistoryEvidence, 'overview'>; t: TFunction }) {
  return (
    <section aria-label={t('exploreLog.overview')}>
      <h3>{t('exploreLog.overview')}</h3>
      {statistics.overview.kind === 'error' ? (
        <Alert type="warning" showIcon message={t('exploreLog.statisticsUnavailable')} />
      ) : (
        <dl className={styles.overviewStats}>
          {exploreOverviewRows(statistics.overview.data).map(([key, value]) => (
            <div key={key}>
              <dt>{t(`exploreLog.statistics.${key}`)}</dt>
              <dd>{value.toLocaleString()}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

function Trend({
  statistics,
  timeWindow,
  runtimeIdentity,
  t
}: {
  statistics: Pick<LogHistoryEvidence, 'trend'>;
  timeWindow: ExactTimeWindow;
  runtimeIdentity: string;
  t: TFunction;
}) {
  const rows = statistics.trend.kind === 'ready' ? Object.keys(statistics.trend.data.hourlyStats) : [];
  return (
    <section aria-label={t('exploreLog.trend')}>
      <h3>{t('exploreLog.trend')}</h3>
      {statistics.trend.kind === 'error' ? (
        <Alert type="warning" showIcon message={t('exploreLog.statisticsUnavailable')} />
      ) : rows.length === 0 ? (
        <p>{t('exploreLog.trendEmpty')}</p>
      ) : (
        <TrendResult trend={statistics.trend.data} timeWindow={timeWindow} runtimeIdentity={runtimeIdentity} t={t} />
      )}
    </section>
  );
}

function TrendResult({
  trend,
  timeWindow,
  runtimeIdentity,
  t
}: {
  trend: Extract<LogHistoryEvidence['trend'], { kind: 'ready' }>['data'];
  timeWindow: ExactTimeWindow;
  runtimeIdentity: string;
  t: TFunction;
}) {
  const result = createLogTrendPersesResult(trend, timeWindow, runtimeIdentity);
  return (
    <HertzBeatMetricTimeSeriesResult
      className={styles.trendChart}
      title={t('exploreLog.trend')}
      ariaLabel={t('exploreLog.trend')}
      query={result.query}
      outcome={result.outcome}
      runtimeIdentity={result.runtimeIdentity}
      messages={explorePersesMessages(t)}
    />
  );
}
