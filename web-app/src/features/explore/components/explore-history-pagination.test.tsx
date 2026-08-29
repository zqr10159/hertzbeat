/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { App } from 'antd';
import type { TFunction } from 'i18next';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { LogExploreQuery, TraceExploreQuery } from '../model/explore-query';
import { ExploreHistoryPagination } from './explore-history-pagination';

const t = ((key: string) => key) as TFunction;

describe('Explore history pagination', () => {
  afterEach(cleanup);

  it.each([
    { signal: 'logs' as const, query: scopedQuery('logs') },
    { signal: 'traces' as const, query: scopedQuery('traces') }
  ])('writes the canonical $signal page to the Explore URL', ({ signal, query }) => {
    const openPath = vi.fn();
    renderPagination(query, true, openPath);

    fireEvent.click(screen.getByTitle('2'));

    const path = openPath.mock.calls[0]?.[0] as string;
    const params = new URLSearchParams(path.slice(path.indexOf('?') + 1));
    expect(Object.fromEntries(params)).toMatchObject({
      signal,
      page: '1',
      start: '1000',
      end: '2000',
      timeZone: 'UTC',
      serviceName: 'checkout'
    });
  });

  it('disables pagination while retained evidence is stale', () => {
    const openPath = vi.fn();
    const view = renderPagination(scopedQuery('logs'), false, openPath);

    expect(view.container.querySelector('.ant-pagination-disabled')).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('2'));
    expect(openPath).not.toHaveBeenCalled();
  });
});

function renderPagination(
  query: LogExploreQuery | TraceExploreQuery,
  enabled: boolean,
  openPath: (path: string) => void
) {
  return render(
    <App>
      <ExploreHistoryPagination
        page={{ content: [], totalElements: 40, totalPages: 2, number: 0, size: 20 }}
        query={query}
        enabled={enabled}
        openPath={openPath}
        t={t}
      />
    </App>
  );
}

function scopedQuery(signal: 'logs'): LogExploreQuery;
function scopedQuery(signal: 'traces'): TraceExploreQuery;
function scopedQuery(signal: 'logs' | 'traces'): LogExploreQuery | TraceExploreQuery {
  return {
    signal,
    timeRange: 'last-30m',
    serviceName: 'checkout',
    start: 1_000,
    end: 2_000,
    timeZone: 'UTC'
  };
}
