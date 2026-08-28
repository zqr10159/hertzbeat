/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Table, Tag } from 'antd';
import type { TFunction } from 'i18next';

import { PersesTimeSeries } from '@/platform/perses';

import type { MetricConsole } from '../model/explore-signal-contract';
import { metricPoints, type MetricSeries } from '../model/explore-signal-model';
import styles from './metric-result.module.css';
import { SignalResultFrame } from './signal-result-frame';

const METRIC_SAMPLE_LIMIT = 100;
const METRIC_TABLE_SCROLL = { x: 760, y: 320 };
const METRIC_NAME_LABEL = '__name__';

type SampleRow = {
  key: string;
  seriesKey: string;
  seriesName: string;
  timestamp: number;
  value: number;
  unit?: string | undefined;
};

export function MetricReadyResult({ data, series, t }: { data: MetricConsole; series: MetricSeries[]; t: TFunction }) {
  const samples = buildSampleRows(series);
  return (
    <SignalResultFrame
      title={t('explore.signals.metrics')}
      count={data.stats?.totalSeries ?? series.length}
      unit={t('exploreMetric.series')}
      meta={[
        { label: t('explore.samples'), value: samples.length },
        { label: t('exploreMetric.datasource'), value: data.datasource ?? '—' },
        { label: t('exploreMetric.queryMode'), value: data.queryMode ?? '—' }
      ]}
    >
      <MetricTrend data={data} series={series} t={t} />
      <MetricSampleTable series={series} samples={samples} t={t} />
    </SignalResultFrame>
  );
}

function MetricTrend({ data, series, t }: { data: MetricConsole; series: MetricSeries[]; t: TFunction }) {
  return (
    <section className={styles.chartSection} aria-label={t('exploreMetric.trend')}>
      <PersesTimeSeries
        className={styles.chart}
        title={t('explore.signals.metrics')}
        ariaLabel={t('exploreMetric.trend')}
        errorFallback={t('exploreMetric.chartUnavailable')}
        series={series.map(item => ({ ...item, points: metricPoints(item) }))}
        timeWindow={metricTimeWindow(data)}
      />
    </section>
  );
}

function MetricSampleTable({ series, samples, t }: { series: MetricSeries[]; samples: SampleRow[]; t: TFunction }) {
  return (
    <Table<SampleRow>
      rowKey="key"
      size="small"
      dataSource={samples.slice(-METRIC_SAMPLE_LIMIT).reverse()}
      pagination={false}
      scroll={METRIC_TABLE_SCROLL}
      columns={[
        {
          title: t('explore.time'),
          dataIndex: 'timestamp',
          render: value => new Date(value as number).toLocaleString()
        },
        { title: t('explore.metric'), dataIndex: 'seriesName' },
        {
          title: t('exploreMetric.value'),
          dataIndex: 'value',
          render: (value, row) => `${String(value)}${row.unit ? ` ${row.unit}` : ''}`
        },
        {
          title: t('explore.labels'),
          render: (_, row) => <MetricLabels series={series} seriesKey={row.seriesKey} />
        }
      ]}
    />
  );
}

function MetricLabels({ series, seriesKey }: { series: MetricSeries[]; seriesKey: string }) {
  const item = series.find(candidate => candidate.key === seriesKey);
  if (!item) return '—';
  return Object.entries(item.labels)
    .filter(([key]) => key !== METRIC_NAME_LABEL)
    .map(([key, value]) => (
      <Tag key={key}>
        {key}={value}
      </Tag>
    ));
}

function buildSampleRows(series: MetricSeries[]): SampleRow[] {
  return series.flatMap(item =>
    metricPoints(item).map((point, index) => ({
      key: `${item.key}-${point.timestamp}-${index}`,
      seriesKey: item.key,
      seriesName: item.name,
      timestamp: point.timestamp,
      value: point.value,
      unit: item.unit
    }))
  );
}

function metricTimeWindow(data: MetricConsole) {
  const from = data.context?.start;
  const to = data.context?.end;
  return from != null && to != null ? { from, to } : undefined;
}
