/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import { describe, expect, it } from 'vitest';

import appStyles from '@/app/styles.css?raw';

import traceStyles from './explore-investigation-trace.module.css?raw';
import viewStyles from './explore-investigation-view.module.css?raw';

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
      /@media \(max-width:\s*700px\)[\s\S]*:global\(body:has\(\[data-explore-investigation='true'\]\)\)\s*\{[^}]*min-width:\s*0/s
    );
  });

  it('removes secondary shell summaries only from a narrow focused investigation header', () => {
    expect(viewStyles).toMatch(
      /@media \(max-width:\s*700px\)[\s\S]*body:has\(\[data-explore-investigation='true'\]\)[\s\S]*\[data-testid='shell-time-policy'\][\s\S]*\[data-testid='shell-status-greptime'\][\s\S]*\[data-testid='shell-status-collector'\][\s\S]*\{[^}]*display:\s*none/s
    );
    expect(viewStyles).not.toMatch(/\[data-testid='shell-status-server'\]/);
  });
});
