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

import { Button } from 'antd';
import type { TFunction } from 'i18next';

import { OperationalPageHeader, OperationalStatePanel } from '@/shared/operational-page';

import {
  exploreHandoffState,
  signalSelectionPatch,
  type ExploreQuery,
  type ExploreQueryPatch,
  type ExploreSignal
} from '../model/explore-model';
import styles from './explore-workbench.module.css';

const signalKeys: ExploreSignal[] = ['metrics', 'logs', 'traces'];

type Props = {
  query: ExploreQuery;
  t: TFunction;
  updateQuery: (changes: ExploreQueryPatch) => void;
};

export function ExploreWorkbench({ query, t, updateQuery }: Props) {
  const handoffState = exploreHandoffState(query);
  const selectSignal = (signal: ExploreSignal) => {
    if (query.signal === signal) return;
    updateQuery(signalSelectionPatch(signal));
  };
  return (
    <>
      <OperationalPageHeader title={t('explore.title')} description={t('explore.description')} />
      {handoffState === 'invalid' && <OperationalStatePanel kind="error" title={t('explore.handoffInvalid')} />}
      <ExploreSignalNavigation query={query} selectSignal={selectSignal} t={t} updateQuery={updateQuery} />
    </>
  );
}

function ExploreSignalNavigation({
  query,
  selectSignal,
  t,
  updateQuery
}: Pick<Props, 'query' | 't' | 'updateQuery'> & { selectSignal: (signal: ExploreSignal) => void }) {
  return (
    <div className={styles.navigationRow}>
      <nav className={styles.signalNavigation} aria-label={t('explore.signalsNavigation')} role="tablist">
        {signalKeys.map(signal => (
          <button
            key={signal}
            type="button"
            role="tab"
            id={`explore-tab-${signal}`}
            aria-controls={`explore-panel-${signal}`}
            aria-selected={query.signal === signal}
            className={(query.signal === signal ? styles.activeSignal : styles.signal) ?? ''}
            onClick={() => selectSignal(signal)}
          >
            {t(`explore.signals.${signal}`)}
          </button>
        ))}
      </nav>
      {query.signal === 'logs' && (
        <div className={styles.logMode} aria-label={t('exploreLog.mode')} role="tablist">
          <Button
            role="tab"
            aria-selected={!query.live}
            aria-controls="explore-panel-logs"
            type={query.live ? 'text' : 'primary'}
            onClick={() => updateQuery({ live: undefined })}
          >
            {t('exploreLog.query')}
          </Button>
          <Button
            role="tab"
            aria-selected={Boolean(query.live)}
            aria-controls="explore-panel-logs"
            type={query.live ? 'primary' : 'text'}
            onClick={() => updateQuery({ live: true })}
          >
            {t('exploreLog.live')}
          </Button>
        </div>
      )}
    </div>
  );
}
