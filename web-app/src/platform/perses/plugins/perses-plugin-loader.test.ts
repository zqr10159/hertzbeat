/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import { getPluginModuleCompoundKey } from '@perses-dev/plugin-system';
import { TimeSeriesChart } from '@perses-dev/timeseries-chart-plugin/lib/index.js';
import { describe, expect, it } from 'vitest';

import { HERTZBEAT_SNAPSHOT_QUERY_KIND, HertzBeatSnapshotTimeSeriesQuery } from './hertzbeat-snapshot-query';
import { hertzBeatPersesPluginLoader } from './perses-plugin-loader';

describe('HertzBeat Perses plugin loader', () => {
  it('registers the HertzBeat query bridge and official TimeSeriesChart with compound keys', async () => {
    const resources = await hertzBeatPersesPluginLoader.getInstalledPlugins();
    expect(resources.map(resource => resource.metadata.name)).toEqual([
      'hertzbeat-perses-runtime',
      '@perses-dev/timeseries-chart-plugin'
    ]);

    const snapshotResource = resources[0]!;
    const loaded = (await hertzBeatPersesPluginLoader.importPluginModule(snapshotResource)) as Record<string, unknown>;
    const compoundKey = getPluginModuleCompoundKey({
      kind: 'TimeSeriesQuery',
      name: HERTZBEAT_SNAPSHOT_QUERY_KIND,
      version: '2.0.0'
    });
    expect(loaded[compoundKey]).toBe(HertzBeatSnapshotTimeSeriesQuery);

    const timeSeriesResource = resources[1]!;
    const timeSeriesModule = (await hertzBeatPersesPluginLoader.importPluginModule(timeSeriesResource)) as Record<
      string,
      unknown
    >;
    const timeSeriesCompoundKey = getPluginModuleCompoundKey({
      kind: 'Panel',
      name: 'TimeSeriesChart',
      version: '0.13.0'
    });
    expect(timeSeriesModule[timeSeriesCompoundKey]).toBe(TimeSeriesChart);
  });
});
