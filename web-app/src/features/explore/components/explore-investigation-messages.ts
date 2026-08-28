/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */

import type { TFunction } from 'i18next';

import type { HertzBeatPersesPrimitiveMessages } from '@/platform/perses';

export function investigationPrimitiveMessages(t: TFunction): HertzBeatPersesPrimitiveMessages {
  return {
    loading: t('exploreInvestigation.query.loading'),
    empty: t('exploreInvestigation.query.empty'),
    truncated: t('exploreInvestigation.query.truncated'),
    truncationUnknown: t('exploreInvestigation.query.truncationUnknown'),
    runtimeError: t('exploreInvestigation.query.runtimeError'),
    failures: {
      'perses.query.invalid': t('exploreInvestigation.query.invalid'),
      'perses.query.permission': t('exploreInvestigation.query.permission'),
      'perses.query.overloaded': t('exploreInvestigation.query.overloaded'),
      'perses.query.unavailable': t('exploreInvestigation.query.unavailable'),
      'perses.query.contract': t('exploreInvestigation.query.contract')
    }
  };
}
