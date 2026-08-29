/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { Pagination } from 'antd';
import type { TFunction } from 'i18next';

import { buildExplorePath } from '../model/explore-model';
import type { ExplorePageResult } from '../model/explore-signal-contract';
import type { LogExploreQuery, TraceExploreQuery } from '../model/explore-query';
import styles from './explore-history-result.module.css';

export function ExploreHistoryPagination({
  page,
  query,
  enabled,
  openPath,
  t
}: {
  page: ExplorePageResult<unknown>;
  query: LogExploreQuery | TraceExploreQuery;
  enabled: boolean;
  openPath: (path: string) => void;
  t: TFunction;
}) {
  if (page.totalPages <= 1) return null;
  return (
    <nav className={styles.pagination} aria-label={t('explore.perses.pagination')}>
      <Pagination
        current={page.number + 1}
        pageSize={page.size}
        total={page.totalElements}
        showSizeChanger={false}
        disabled={!enabled}
        onChange={next => {
          if (enabled) openPath(buildExplorePath({ ...query, pageIndex: next - 1 || undefined }));
        }}
      />
    </nav>
  );
}
