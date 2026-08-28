/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { usePublishShellInvestigation } from '@/shared/investigation';
import { useSharedTimeOptional } from '@/shared/time';

import { ExploreResultFrame } from '../components/explore-state-panel';
import { LogResult } from '../components/log-result';
import { materializeLogInvestigation } from '../model/explore-agent-handoff';
import { buildLogInvestigationPath, buildTraceInvestigationPath } from '../model/explore-investigation-model';
import type { LogExploreQuery } from '../model/explore-model';
import type { LogHistoryEvidence, LogRow } from '../model/explore-signal-contract';

export function ExploreLogPanel({
  data,
  statistics,
  query,
  openPath,
  evidenceCurrent
}: {
  data: LogHistoryEvidence['page'];
  statistics: Pick<LogHistoryEvidence, 'overview' | 'trend'>;
  query: LogExploreQuery;
  openPath: (path: string) => void;
  evidenceCurrent: boolean;
}) {
  const { t } = useTranslation();
  const sharedTime = useSharedTimeOptional();
  const evidence = useMemo(
    () =>
      evidenceCurrent
        ? {
            totalElements: data.totalElements,
            number: data.number,
            size: data.size,
            contentCount: data.content.length
          }
        : undefined,
    [data.content.length, data.number, data.size, data.totalElements, evidenceCurrent]
  );
  const investigation = useMemo(
    () => materializeLogInvestigation(query, evidence, sharedTime?.window),
    [evidence, query, sharedTime?.window]
  );
  usePublishShellInvestigation(investigation);
  const navigation = createLogFocusedNavigation(query, sharedTime?.window, evidenceCurrent, openPath);
  return (
    <ExploreResultFrame>
      <LogResult
        data={data}
        statistics={statistics}
        query={query}
        t={t}
        navigate={openPath}
        evidenceCurrent={evidenceCurrent}
        onSelectLog={navigation.openLog}
        onOpenTrace={navigation.openTrace}
      />
    </ExploreResultFrame>
  );
}

function createLogFocusedNavigation(
  query: LogExploreQuery,
  window: { from: number; to: number } | undefined,
  evidenceCurrent: boolean,
  openPath: (path: string) => void
) {
  return {
    openLog: (row: LogRow) => {
      if (!evidenceCurrent || !window || !row.logRecordUid) return;
      openPath(
        buildLogInvestigationPath(
          query,
          {
            logRecordUid: row.logRecordUid,
            timeUnixNano: row.timeUnixNano,
            traceId: row.traceId,
            spanId: row.spanId
          },
          window,
          browserTimeZone()
        )
      );
    },
    openTrace: (row: LogRow) => {
      if (!evidenceCurrent || !window || !row.traceId) return;
      openPath(
        buildTraceInvestigationPath(
          query,
          { traceId: row.traceId, selectedSpanId: row.spanId, startTime: null, durationNanos: null },
          window,
          browserTimeZone()
        )
      );
    }
  };
}

function browserTimeZone() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
