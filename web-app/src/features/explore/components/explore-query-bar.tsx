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

import { Button, Input, Select } from 'antd';
import type { TFunction } from 'i18next';

import { OperationalCommandBar } from '@/shared/operational-page';
import { globalAutoRefreshValues, type SharedTimeValue } from '@/shared/time';

import {
  EXPLORE_TIME_RANGES,
  exploreUsesExactWindow,
  presetTimeRangePatch,
  type ExploreQuery,
  type ExploreQueryPatch,
  type ExploreTimeRange
} from '../model/explore-model';
import type { ExploreSubmissionViewModel } from '../model/explore-submission-model';
import { ExploreActiveFilters } from './explore-active-filters';
import { ExploreAdvancedFilters, ExploreGuidedFilters } from './explore-advanced-filters';
import styles from './explore-query-bar.module.css';

type Props = {
  query: ExploreQuery;
  t: TFunction;
  updateQuery: (changes: ExploreQueryPatch) => void;
  updateScope: (changes: ExploreQueryPatch) => void;
  refresh: () => Promise<void>;
  time: SharedTimeValue | null | undefined;
  submission: ExploreSubmissionViewModel;
};

const EXACT_WINDOW_OPTION = 'exact-window';

export function ExploreQueryBar({ query, t, updateQuery, updateScope, refresh, time, submission }: Props) {
  const { draft, errors, updateField } = submission;
  return (
    <form
      className={styles.form}
      aria-label={t('explore.queryToolbar')}
      onSubmit={event => {
        event.preventDefault();
        submission.submit();
      }}
    >
      <OperationalCommandBar
        primary={
          <div className={styles.commandFields}>
            <ExploreTimeRange query={query} t={t} updateScope={updateScope} />
            <Input
              className={styles.queryInput ?? ''}
              value={draft.query}
              aria-label={t(`explore.queryLabels.${query.signal}`)}
              onChange={event => updateField({ field: 'query', value: event.target.value })}
              placeholder={t(`explore.queryPlaceholders.${query.signal}`)}
            />
            <ExploreAutoRefresh query={query} t={t} time={time} />
          </div>
        }
        secondary={
          <div className={styles.commandActions}>
            <Button className={styles.run ?? ''} type="primary" htmlType="submit">
              {t('common.query')}
            </Button>
            <Button onClick={() => void refresh()}>{t('common.refresh')}</Button>
          </div>
        }
      />
      <ExploreGuidedFilters draft={draft} t={t} updateField={updateField} />
      <ExploreAdvancedFilters draft={draft} errors={errors} t={t} updateField={updateField} />
      <ExploreActiveFilters query={query} t={t} updateQuery={updateQuery} removeFilter={submission.removeFilter} />
    </form>
  );
}

function ExploreTimeRange({ query, t, updateScope }: Pick<Props, 'query' | 't' | 'updateScope'>) {
  const exactWindow = exploreUsesExactWindow(query);
  const exactOption = exactWindow
    ? [{ value: EXACT_WINDOW_OPTION, label: t('explore.exactWindow'), disabled: true }]
    : [];
  return (
    <Select<string>
      className={styles.timeRange ?? ''}
      aria-label={t('explore.timeRange')}
      value={exactWindow ? EXACT_WINDOW_OPTION : query.timeRange}
      options={[
        ...exactOption,
        ...EXPLORE_TIME_RANGES.map(value => ({ value, label: t(`explore.timeRanges.${value}`) }))
      ]}
      onChange={value => updateTimeRange(query, value, updateScope)}
    />
  );
}

function ExploreAutoRefresh({ query, t, time }: Pick<Props, 'query' | 't' | 'time'>) {
  const fixedWindowFields = query.start != null || query.end != null;
  if (fixedWindowFields || !time) return null;
  return (
    <Select<number>
      className={styles.timeRange ?? ''}
      aria-label={autoRefreshLabel(time.autoRefreshMs, t)}
      value={time.autoRefreshMs}
      options={globalAutoRefreshValues.map(interval => ({ value: interval, label: autoRefreshLabel(interval, t) }))}
      onChange={interval => time.setAutoRefresh(interval)}
    />
  );
}

function updateTimeRange(query: ExploreQuery, value: string, updateScope: Props['updateScope']) {
  if (!EXPLORE_TIME_RANGES.includes(value as ExploreTimeRange)) return;
  updateScope(presetTimeRangePatch(query, value as ExploreTimeRange));
}

function autoRefreshLabel(interval: number, t: TFunction) {
  if (interval === 0) return t('shell.time.autoRefreshOff');
  return t('shell.time.autoRefreshSeconds', { seconds: interval / 1_000 });
}
