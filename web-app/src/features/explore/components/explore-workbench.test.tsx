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

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { I18nextProvider, useTranslation } from 'react-i18next';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { i18n, initializeI18n, loadLocale } from '@/core/i18n/i18n';
import en from '@/assets/i18n/en-us.json';

import { ExploreQueryBar } from './explore-query-bar';
import { ExploreWorkbench } from './explore-workbench';
import type { ExploreQueryPatch } from '../model/explore-model';
import { draftFromQuery } from '../model/explore-submission-model';
import type { SharedTimeValue } from '@/shared/time';

describe('Explore workbench', () => {
  beforeAll(async () => {
    await initializeI18n();
    await loadLocale('en-US');
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('keeps one accessible query command region with query, time, refresh, and Run controls', () => {
    const updateQuery = vi.fn();
    render(
      <I18nextProvider i18n={i18n}>
        <WorkbenchSubject updateQuery={updateQuery} />
      </I18nextProvider>
    );

    const command = screen.getByRole('form', { name: 'Explore query controls' });
    expect(within(command).getByRole('textbox', { name: 'Metrics query' })).toBeInTheDocument();
    expect(within(command).getByRole('combobox', { name: 'Time range' })).toBeInTheDocument();
    expect(within(command).getByRole('combobox', { name: /Auto refresh/u })).toBeInTheDocument();
    expect(within(command).getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
    expect(within(command).getByRole('button', { name: 'Query' })).toBeInTheDocument();
    expect(screen.getByRole('banner')).not.toContainElement(
      document.querySelector('[data-hb-operational-page-actions]')
    );
    fireEvent.click(screen.getByRole('tab', { name: 'Metrics' }));
    expect(updateQuery).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('tab', { name: 'Logs' }));
    expect(updateQuery).toHaveBeenCalledWith({
      signal: 'logs',
      query: undefined,
      live: undefined,
      pageIndex: undefined
    });
  });

  it('offers only shared auto-refresh values for relative windows', () => {
    const time = sharedTime();
    render(
      <I18nextProvider i18n={i18n}>
        <WorkbenchSubject updateQuery={vi.fn()} time={time} />
      </I18nextProvider>
    );

    fireEvent.mouseDown(
      within(screen.getByRole('form', { name: 'Explore query controls' })).getByRole('combobox', {
        name: /Auto refresh/u
      })
    );
    fireEvent.click(screen.getByText('Auto refresh 30s'));
    expect(time.setAutoRefresh).toHaveBeenCalledWith(30_000);
  });

  it('keeps raw log attributes behind an advanced disclosure', () => {
    render(
      <I18nextProvider i18n={i18n}>
        <QuerySubject />
      </I18nextProvider>
    );
    expect(screen.getByText('Advanced filters').closest('details')).not.toHaveAttribute('open');
    expect(screen.getByText('Add filters').closest('summary')?.querySelector('.anticon-filter')).not.toBeNull();
  });

  it('edits optional QueryContext v1 dimensions as an instance and HTTP route template', () => {
    const query = {
      signal: 'logs',
      timeRange: 'last-30m',
      instance: 'checkout-7d9',
      endpoint: '/checkout'
    } as const;
    render(
      <I18nextProvider i18n={i18n}>
        <ExploreQueryBar
          query={query}
          t={i18n.t}
          updateQuery={vi.fn()}
          updateScope={vi.fn()}
          refresh={vi.fn().mockResolvedValue(undefined)}
          time={sharedTime()}
          submission={{
            draft: draftFromQuery(query),
            errors: {},
            updateField: vi.fn(),
            submit: vi.fn(),
            removeFilter: vi.fn()
          }}
        />
      </I18nextProvider>
    );

    expect(screen.getByPlaceholderText('Service instance ID')).toHaveValue('checkout-7d9');
    expect(screen.getByPlaceholderText('HTTP route template, for example /checkout')).toHaveValue('/checkout');
    expect(screen.getByText('Instance: checkout-7d9')).toBeInTheDocument();
    expect(screen.getByText('HTTP route: /checkout')).toBeInTheDocument();
  });

  it('delegates refresh without rewriting a scoped fixed window and exposes invalid handoffs', () => {
    const updateQuery = vi.fn();
    const refresh = vi.fn().mockResolvedValue(undefined);
    render(
      <I18nextProvider i18n={i18n}>
        <ExploreWorkbench
          query={{
            signal: 'metrics',
            timeRange: 'last-30m',
            serviceName: 'checkout-api',
            serviceNamespace: 'commerce',
            environment: 'prod',
            collectorId: 'collector-east',
            start: 1_710_000_000_000,
            end: 1_710_000_005_000
          }}
          t={i18n.t}
          updateQuery={updateQuery}
        />
        <ExploreQueryBar
          query={{
            signal: 'metrics',
            timeRange: 'last-30m',
            serviceName: 'checkout-api',
            serviceNamespace: 'commerce',
            environment: 'prod',
            collectorId: 'collector-east',
            start: 1_710_000_000_000,
            end: 1_710_000_005_000
          }}
          t={i18n.t}
          updateQuery={vi.fn()}
          updateScope={updateQuery}
          refresh={refresh}
          time={sharedTime({ autoRefreshMs: 0 })}
          submission={{
            draft: draftFromQuery({
              signal: 'metrics',
              timeRange: 'last-30m',
              start: 1_710_000_000_000,
              end: 1_710_000_005_000
            }),
            errors: {},
            updateField: vi.fn(),
            submit: vi.fn(),
            removeFilter: vi.fn()
          }}
        />
      </I18nextProvider>
    );

    expect(screen.getByText('Fixed time window')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(refresh).toHaveBeenCalledOnce();
    expect(updateQuery).not.toHaveBeenCalled();
    cleanup();
    render(
      <I18nextProvider i18n={i18n}>
        <ExploreWorkbench
          query={{ signal: 'metrics', timeRange: 'last-30m', collectorId: 'collector-east', start: 2_000, end: 1_000 }}
          t={i18n.t}
          updateQuery={vi.fn()}
        />
      </I18nextProvider>
    );
    expect(screen.getByText(en.explore.handoffInvalid)).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: /Auto refresh/u })).not.toBeInTheDocument();
  });
});

function WorkbenchSubject({
  updateQuery,
  time = sharedTime()
}: {
  updateQuery: (changes: ExploreQueryPatch) => void;
  time?: SharedTimeValue;
}) {
  const { t } = useTranslation();
  return (
    <>
      <ExploreWorkbench
        query={{ signal: 'metrics', timeRange: 'last-30m', query: 'http_requests_total' }}
        t={t}
        updateQuery={updateQuery}
      />
      <QuerySubject
        query={{ signal: 'metrics', timeRange: 'last-30m', query: 'http_requests_total' }}
        time={time}
        updateScope={updateQuery}
        refresh={vi.fn().mockResolvedValue(undefined)}
      />
    </>
  );
}

function sharedTime(override: Partial<SharedTimeValue> = {}): SharedTimeValue {
  return {
    policy: 'route_owned',
    headerMode: 'exact_window',
    manualRefreshOwner: 'time_revision',
    window: { from: 1_000, to: 2_000 },
    range: '30m',
    autoRefreshMs: 0,
    remainingMs: null,
    refreshRevision: 0,
    setRange: vi.fn(),
    setAutoRefresh: vi.fn(),
    commitWindow: vi.fn(),
    requestRefresh: vi.fn(),
    ...override
  };
}

function QuerySubject({
  query = { signal: 'logs', timeRange: 'last-30m' },
  time = sharedTime(),
  updateScope = vi.fn(),
  refresh = vi.fn().mockResolvedValue(undefined)
}: {
  query?: Parameters<typeof draftFromQuery>[0];
  time?: SharedTimeValue;
  updateScope?: (changes: ExploreQueryPatch) => void;
  refresh?: () => Promise<void>;
} = {}) {
  const { t } = useTranslation();
  return (
    <ExploreQueryBar
      query={query}
      t={t}
      updateQuery={vi.fn()}
      updateScope={updateScope}
      refresh={refresh}
      time={time}
      submission={{
        draft: draftFromQuery(query),
        errors: {},
        updateField: vi.fn(),
        submit: vi.fn(),
        removeFilter: vi.fn()
      }}
    />
  );
}
