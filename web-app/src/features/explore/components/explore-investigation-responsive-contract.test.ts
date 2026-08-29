/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { describe, expect, it } from 'vitest';

import appStyles from '@/app/styles.css?raw';

import traceStyles from './explore-investigation-trace.module.css?raw';
import viewStyles from './explore-investigation-view.module.css?raw';
import queryStyles from './explore-query-bar.module.css?raw';
import historyStyles from './explore-history-result.module.css?raw';
import logStyles from './log-result.module.css?raw';
import resultFrameStyles from './signal-result-frame.module.css?raw';
import workspaceStyles from './explore-workbench.module.css?raw';
import persesStyles from '@/platform/perses/runtime/hertzbeat-perses-primitives.module.css?raw';

describe('Explore investigation responsive contract', () => {
  it('keeps the Gantt readable at ordinary desktop widths and contains narrow runtime overflow', () => {
    expect(traceStyles).not.toMatch(
      /@media \(min-width:\s*1180px\)[\s\S]*\.tracePrimary\s*\{[^}]*grid-template-columns/s
    );
    expect(traceStyles).toMatch(
      /@media \(min-width:\s*1680px\)[\s\S]*\.tracePrimary\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*2fr\)\s+minmax\(280px,\s*1fr\)/s
    );
    expect(traceStyles).toMatch(/\.spanInspector\s*\{[^}]*border-top:\s*1px solid/s);
    expect(traceStyles).toMatch(
      /@media \(min-width:\s*1680px\)[\s\S]*\.spanInspector\s*\{[^}]*border-left:\s*1px solid/s
    );
    expect(traceStyles).toMatch(/\.ganttRuntime\s*\{[^}]*min-width:\s*0[^}]*overflow-x:\s*auto/s);
  });

  it('releases the shared desktop body floor only while a narrow focused investigation is mounted', () => {
    expect(appStyles).toMatch(/body\s*\{[^}]*min-width:\s*1024px/s);
    expect(viewStyles).toMatch(
      /@media \(max-width:\s*768px\)[\s\S]*:global\(body:has\(\[data-explore-investigation='true'\]\)\)\s*\{[^}]*min-width:\s*0/s
    );
  });

  it('removes secondary shell summaries only from a narrow focused investigation header', () => {
    expect(viewStyles).toMatch(
      /@media \(max-width:\s*768px\)[\s\S]*body:has\(\[data-explore-investigation='true'\]\)[\s\S]*\[data-testid='shell-time-policy'\][\s\S]*\[data-testid='shell-status-greptime'\][\s\S]*\[data-testid='shell-status-collector'\][\s\S]*\{[^}]*display:\s*none/s
    );
    expect(viewStyles).not.toMatch(/\[data-testid='shell-status-server'\]/);
  });

  it('contains ordinary Explore through tablet width without changing the desktop shell floor', () => {
    expect(workspaceStyles).toMatch(
      /@media \(max-width:\s*768px\)[\s\S]*body:has\(\[data-explore-workspace='true'\]\)[\s\S]*min-width:\s*0/s
    );
    expect(workspaceStyles).toMatch(
      /body:has\(\[data-explore-workspace='true'\]\)[\s\S]*\[data-testid='shell-time-policy'\][\s\S]*\[data-testid='shell-status-greptime'\][\s\S]*\[data-testid='shell-status-collector'\][\s\S]*display:\s*none/s
    );
    expect(workspaceStyles).not.toMatch(/\[data-testid='shell-status-server'\]/);
    expect(queryStyles).toMatch(/@media \(max-width:\s*700px\)[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)/s);
    expect(appStyles).toMatch(/body\s*\{[^}]*min-width:\s*1024px/s);
  });

  it('keeps Logs overview, trend, and result on flat full-width workbench surfaces', () => {
    expect(logStyles).toMatch(/\.statistics\s*\{[^}]*flex-direction:\s*column/s);
    expect(logStyles).toMatch(/\.statistics\s*\{[^}]*margin-bottom:\s*0/s);
    expect(logStyles).toMatch(
      /\.statistics\s*>\s*\.overview\s*\{[^}]*display:\s*grid[^}]*grid-template-columns:\s*auto\s+minmax\(0,\s*1fr\)/s
    );
    expect(logStyles).not.toMatch(/\.statistics\s*>\s*section\s*\{[^}]*border-bottom:/s);
    expect(logStyles).not.toMatch(/\.statistics\s*>\s*section\s*\{[^}]*border-radius:/s);
    expect(logStyles).not.toMatch(/\.statistics\s*\{[^}]*grid-template-columns:/s);
    expect(historyStyles).toMatch(/\.logRegion\s*\{[^}]*padding-block:\s*12px/s);
    expect(historyStyles).toMatch(/\.logRegion\s*\+\s*\.logRegion\s*\{[^}]*border-top:\s*1px solid/s);
  });

  it('flattens embedded Perses cards and keeps collapsed host actions on a flat scrollable disclosure', () => {
    expect(persesStyles).toMatch(/\.runtime\s+:global\(\.MuiCard-root\)[\s\S]*border-radius:\s*0/s);
    expect(persesStyles).toMatch(/\.runtime\s+:global\(\.MuiPaper-root\)[\s\S]*box-shadow:\s*none/s);
    expect(persesStyles).toMatch(/\.runtime\s+:global\(\.MuiTableContainer-root\)[^}]*overflow-x:\s*auto/s);
    expect(persesStyles).toMatch(
      /\.runtime\s+:global\(\.MuiCardContent-root\s*>\s*\.MuiBox-root\)[^}]*box-shadow:\s*none/s
    );
    expect(persesStyles).toMatch(/\.interactionList\s+li\s*>\s*div\s*\{[^}]*overflow-x:\s*auto/s);
    expect(persesStyles).toMatch(/\.interactions\s+button\s*\{[^}]*white-space:\s*nowrap/s);
    expect(persesStyles).toMatch(
      /@media \(width <= 700px\)[\s\S]*\.interactionList\s+li\s*>\s*div\s*\{[^}]*flex-basis:\s*auto/s
    );
    expect(persesStyles).toMatch(
      /\.interactions\s*\{[^}]*border:\s*0[^}]*border-radius:\s*0[^}]*background:\s*transparent[^}]*box-shadow:\s*none/s
    );
  });

  it('keeps narrow Logs readable with stable content width and runtime-local scrolling', () => {
    expect(historyStyles).toMatch(/\.persesFrame\s*\{[^}]*min-width:\s*0[^}]*overflow:\s*hidden/s);
    expect(persesStyles).toMatch(
      /\.runtime:has\(>\s*:global\(\[data-perses-primitive='logs-table'\]\)\)\s*\{[^}]*overflow-x:\s*auto/s
    );
    expect(persesStyles).toMatch(
      /\.runtime\s*>\s*:global\(\[data-perses-primitive='logs-table'\]\)\s*\{[^}]*min-width:\s*640px/s
    );
  });

  it('gives runtime, completeness, and interactions independent grid rows', () => {
    expect(persesStyles).toMatch(/\.primitive\s*\{[^}]*grid-template-rows:\s*360px\s+auto\s+auto/s);
    expect(persesStyles).toMatch(
      /\.primitive\[data-variant='compact'\]\s*\{[^}]*min-height:\s*180px[^}]*grid-template-rows:\s*180px\s+auto\s+auto/s
    );
    expect(persesStyles).toMatch(
      /\.primitive\[data-variant='compact'\]\s+\.runtime,[\s\S]*height:\s*180px[^}]*min-height:\s*180px/s
    );
  });

  it('keeps focused signal facts flat inside their single outer surface', () => {
    expect(viewStyles).toMatch(/\.signalSection\s*\{[^}]*border:\s*1px solid[^}]*border-radius:/s);
    expect(viewStyles).toMatch(/\.fact\s*\{[^}]*border-bottom:\s*1px solid/s);
    expect(viewStyles).not.toMatch(/\.fact\s*\{[^}]*border:\s*1px solid/s);
    expect(viewStyles).not.toMatch(/\.fact\s*\{[^}]*border-radius:/s);
  });

  it('uses tabular figures for ordinary Logs overview and result counts', () => {
    expect(logStyles).toMatch(/\.overviewStats\s+dd\s*\{[^}]*font-variant-numeric:\s*tabular-nums/s);
    expect(queryStyles).toMatch(/\.commandFields\s*\{[^}]*min-width:\s*0/s);
    expect(resultFrameStyles).toMatch(/\.identity\s+span\s*\{[^}]*font-variant-numeric:\s*tabular-nums/s);
  });
});
