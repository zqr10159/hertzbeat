/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { i18n, initializeI18n, loadLocale } from '@/core/i18n/i18n';

vi.mock('@/platform/perses', () => ({
  HertzBeatLogsTableResult: () => <div>Perses log rows</div>,
  HertzBeatMetricTimeSeriesResult: ({
    onTimeWindowChange,
    timeWindowChangeEnabled
  }: {
    onTimeWindowChange?: ((window: { from: number; to: number }) => void) | undefined;
    timeWindowChangeEnabled?: boolean | undefined;
  }) => (
    <button
      type="button"
      disabled={!timeWindowChangeEnabled}
      onClick={() => onTimeWindowChange?.({ from: evidenceWindow.from + 60_000, to: evidenceWindow.to - 60_000 })}
    >
      Zoom trend
    </button>
  )
}));

import type { LogExploreQuery } from '../model/explore-query';
import type { LogHistoryEvidence } from '../model/explore-signal-contract';
import { ExplorePersesLogPanel } from './explore-perses-log-panel';

const evidenceWindow = { from: 1_750_000_000_000, to: 1_750_003_600_000 } as const;

describe('ExplorePersesLogPanel trend ownership', () => {
  beforeAll(async () => {
    await initializeI18n();
    await loadLocale('en-US');
  });
  afterEach(cleanup);

  it('publishes a current trend zoom as a canonical exact query and shows offset-page provenance', () => {
    const openPath = vi.fn();
    renderPanel(query, true, openPath);

    expect(screen.getByText('1 / 57')).toBeInTheDocument();
    expect(screen.getByText('3 / 3')).toBeInTheDocument();
    expect(
      screen.getByText(`${new Date(evidenceWindow.from).toISOString()} – ${new Date(evidenceWindow.to).toISOString()}`)
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Zoom trend' }));

    const params = new URLSearchParams(String(openPath.mock.calls[0]?.[0]).split('?')[1]);
    expect(Object.fromEntries(params)).toMatchObject({
      signal: 'logs',
      serviceName: 'checkout',
      serviceNamespace: 'commerce',
      environment: 'prod',
      query: 'timeout',
      severityText: 'WARN',
      resourceFilter: 'cloud.region=us-east',
      attributeFilter: 'http.status_code=500',
      traceId: '0123456789abcdef0123456789abcdef',
      spanId: '0123456789abcdef',
      start: String(evidenceWindow.from + 60_000),
      end: String(evidenceWindow.to - 60_000)
    });
    expect(params.has('windowMode')).toBe(false);
    expect(params.has('page')).toBe(false);
    expect(params.has('logRecordUid')).toBe(false);
  });

  it('does not expose trend zoom while retained evidence is stale', () => {
    const openPath = vi.fn();
    renderPanel(query, false, openPath);

    expect(screen.getByRole('button', { name: 'Zoom trend' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Zoom trend' }));
    expect(openPath).not.toHaveBeenCalled();
  });

  it('keeps the canonical requested page visible when an empty response reports a different page', () => {
    renderPanel({ ...query, pageIndex: 4 }, true, vi.fn(), {
      ...page,
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: 0
    });

    expect(screen.getByText('5 / 0')).toBeInTheDocument();
  });
});

const query: LogExploreQuery = {
  signal: 'logs',
  timeRange: 'last-30m',
  windowMode: 'preset',
  pageIndex: 2,
  logRecordUid: 'record-1',
  serviceName: 'checkout',
  serviceNamespace: 'commerce',
  environment: 'prod',
  query: 'timeout',
  severityText: 'WARN',
  resourceFilter: 'cloud.region=us-east',
  attributeFilter: 'http.status_code=500',
  traceId: '0123456789abcdef0123456789abcdef',
  spanId: '0123456789abcdef'
};

const page: LogHistoryEvidence['page'] = {
  content: [
    {
      logRecordUid: 'record-1',
      timeUnixNano: '1750000000000000000',
      observedTimeUnixNano: null,
      severityNumber: 9,
      severityText: 'INFO',
      body: 'timeout',
      attributes: {},
      droppedAttributesCount: 0,
      traceId: null,
      spanId: null,
      traceFlags: null,
      resource: {},
      resourceSchemaUrl: null,
      instrumentationScope: null,
      scopeSchemaUrl: null
    }
  ],
  totalElements: 57,
  totalPages: 3,
  number: 2,
  size: 20
};

function renderPanel(
  value: LogExploreQuery,
  evidenceCurrent: boolean,
  openPath: (path: string) => void,
  data: LogHistoryEvidence['page'] = page
) {
  return render(
    <I18nextProvider i18n={i18n}>
      <ExplorePersesLogPanel
        data={data}
        statistics={{
          overview: {
            kind: 'ready',
            data: {
              totalCount: 57,
              traceCount: 1,
              debugCount: 0,
              infoCount: 56,
              warnCount: 1,
              errorCount: 0,
              fatalCount: 0
            }
          },
          trend: {
            kind: 'ready',
            data: { hourlyStats: { '2025-06-15 15:00': 28, '2025-06-15 16:00': 29 } }
          }
        }}
        query={value}
        openPath={openPath}
        timeWindow={evidenceWindow}
        revision={1}
        evidenceCurrent={evidenceCurrent}
      />
    </I18nextProvider>
  );
}
