/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { Component, lazy, Suspense, type ReactNode } from 'react';

import type { ExactTimeWindow } from '@/shared/query-context';

import { loadPersesRuntime } from './perses-runtime-registry';
import type { HertzBeatTimeSeries } from './perses-time-series-model';
import styles from './perses-time-series.module.css';

type PersesRuntimeProps = {
  title: string;
  series: HertzBeatTimeSeries[];
  timeWindow: ExactTimeWindow | undefined;
};

async function loadPersesTimeSeriesRuntime() {
  const module = await loadPersesRuntime('time-series');
  return { default: module.PersesTimeSeriesRuntime };
}

function createPersesTimeSeriesRuntime() {
  return lazy(loadPersesTimeSeriesRuntime);
}

class PersesRuntimeErrorBoundary extends Component<
  PersesRuntimeProps & { ariaLabel: string; errorFallback: ReactNode; resetKeys: readonly unknown[] },
  { failed: boolean; Runtime: ReturnType<typeof createPersesTimeSeriesRuntime> }
> {
  state = { failed: false, Runtime: createPersesTimeSeriesRuntime() };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidUpdate(previousProps: Readonly<typeof this.props>) {
    if (this.state.failed && resetKeysChanged(previousProps.resetKeys, this.props.resetKeys)) {
      this.setState({ failed: false, Runtime: createPersesTimeSeriesRuntime() });
    }
  }

  render() {
    if (this.state.failed) {
      return (
        <span role="status" aria-label={this.props.ariaLabel} data-perses-runtime-state="error">
          {this.props.errorFallback}
        </span>
      );
    }
    const Runtime = this.state.Runtime;
    return (
      <div className={styles.frame} role="img" aria-label={this.props.ariaLabel}>
        <Suspense fallback={<span aria-hidden="true" data-perses-runtime-state="loading" />}>
          <Runtime title={this.props.title} series={this.props.series} timeWindow={this.props.timeWindow} />
        </Suspense>
      </div>
    );
  }
}

function resetKeysChanged(previous: readonly unknown[], current: readonly unknown[]) {
  return previous.length !== current.length || previous.some((value, index) => !Object.is(value, current[index]));
}

export function PersesTimeSeries({
  title,
  ariaLabel,
  series,
  timeWindow,
  errorFallback,
  className
}: {
  title: string;
  ariaLabel: string;
  series: HertzBeatTimeSeries[];
  timeWindow?: ExactTimeWindow | undefined;
  errorFallback: ReactNode;
  className?: string | undefined;
}) {
  return (
    <div className={className} data-visualization-runtime="perses">
      <PersesRuntimeErrorBoundary
        title={title}
        ariaLabel={ariaLabel}
        series={series}
        timeWindow={timeWindow}
        errorFallback={errorFallback}
        resetKeys={[series, timeWindow?.from, timeWindow?.to, title]}
      />
    </div>
  );
}
