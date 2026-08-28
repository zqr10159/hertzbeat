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
import { TraceResult, type TraceDetailView } from '../components/trace-result';
import { materializeTraceInvestigation } from '../model/explore-agent-handoff';
import { buildTraceInvestigationPath } from '../model/explore-investigation-model';
import { buildExplorePath, type TraceExploreQuery } from '../model/explore-model';
import type { ExplorePageResult, TraceRow } from '../model/explore-signal-contract';

export function ExploreTracePanel({
  data,
  query,
  openPath,
  evidenceCurrent
}: {
  data: ExplorePageResult<TraceRow>;
  query: TraceExploreQuery;
  openPath: (path: string) => void;
  evidenceCurrent: boolean;
}) {
  const { t } = useTranslation();
  const sharedTime = useSharedTimeOptional();
  const investigation = useMemo(
    () => materializeTraceInvestigation(query, undefined, sharedTime?.window),
    [query, sharedTime?.window]
  );
  usePublishShellInvestigation(investigation);
  const trace: TraceDetailView = {
    state: { kind: 'closed' },
    openTrace: traceId => {
      const row = data.content.find(item => item.traceId === traceId);
      if (!evidenceCurrent || !sharedTime?.window || !row) return;
      openPath(
        buildTraceInvestigationPath(
          query,
          {
            traceId: row.traceId,
            selectedSpanId: row.rootSpanId,
            startTime: row.startTime,
            durationNanos: row.durationNanos
          },
          sharedTime.window,
          browserTimeZone()
        )
      );
    },
    close: () => undefined,
    selectSpan: () => undefined,
    retry: () => Promise.resolve(),
    changePage: page => openPath(buildExplorePath({ ...query, pageIndex: page - 1 || undefined })),
    openRelatedLogs: () => undefined,
    openRelatedMetrics: () => undefined
  };
  return (
    <ExploreResultFrame>
      <TraceResult data={data} t={t} trace={trace} evidenceCurrent={evidenceCurrent} />
    </ExploreResultFrame>
  );
}

function browserTimeZone() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
