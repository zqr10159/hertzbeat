/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.observability.traces.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.common.entity.manager.EntityIdentity;
import org.apache.hertzbeat.common.entity.manager.ObserveEntity;
import org.apache.hertzbeat.common.observability.gateway.AuthTokenRequestContext;
import org.apache.hertzbeat.common.observability.gateway.AuthTokenScopes;
import org.apache.hertzbeat.common.observability.gateway.ObservabilityWorkspaceQueryGateway;
import org.apache.hertzbeat.common.observability.model.EntityCanonicalIdentityRegistry;
import org.apache.hertzbeat.common.observability.model.ObservedEntityContext;
import org.apache.hertzbeat.common.observability.dto.trace.EntityTraceQueryHintDto;
import org.apache.hertzbeat.common.observability.dto.trace.EntityTraceSummaryDto;
import org.apache.hertzbeat.common.observability.dto.trace.TraceDetailDto;
import org.apache.hertzbeat.common.observability.dto.trace.TraceListItemDto;
import org.apache.hertzbeat.common.observability.dto.trace.TraceOverviewDto;
import org.apache.hertzbeat.common.observability.dto.trace.TraceSpanNodeDto;
import org.apache.hertzbeat.observability.ingestion.enricher.OtlpCorrelationEnricher;
import org.apache.hertzbeat.observability.ingestion.semantic.OtlpResourceSemanticAttributes;
import org.apache.hertzbeat.observability.traces.service.EntityTraceQueryService;
import org.apache.hertzbeat.warehouse.repository.TraceQueryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Read-only trace query service backed by Greptime trace rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EntityTraceQueryServiceImpl implements EntityTraceQueryService {

    private static final int TRACE_LIST_SAMPLE_LIMIT = 1500;
    private static final int TRACE_DETAIL_LIMIT = 5000;
    private static final int DEFAULT_TRACE_LIST_PAGE_INDEX = 0;
    private static final int DEFAULT_TRACE_LIST_PAGE_SIZE = 20;
    private static final int MAX_TRACE_LIST_PAGE_SIZE = 1000;
    private static final int TRACE_GROUP_BY_LIMIT = 20;
    private static final int TRACE_GROUP_BY_MAX_LIMIT = 100;
    private static final long TRACE_GROUP_BY_MAX_MIN_COUNT = 1_000_000L;
    private static final long DEFAULT_LOOKBACK_MILLIS = Duration.ofHours(24).toMillis();
    private static final long ACTIVE_TRACE_WINDOW_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final Set<String> WORKSPACE_RESOURCE_KEYS = Set.of(
            OtlpCorrelationEnricher.WORKSPACE_ID_ATTRIBUTE,
            AuthTokenScopes.CLAIM_WORKSPACE_ID,
            "workspace.id"
    );
    private static final Set<String> ENTITY_SCOPE_RESOURCE_KEYS = Set.of(
            OtlpResourceSemanticAttributes.HERTZBEAT_ENTITY_ID,
            OtlpResourceSemanticAttributes.HERTZBEAT_ENTITY_TYPE,
            "service.name",
            "service.namespace",
            "deployment.environment.name"
    );

    private final TraceQueryRepository traceQueryRepository;
    private final ObservabilityWorkspaceQueryGateway workspaceQueryGateway;
    private final TraceResourceFilterParser resourceFilterParser;
    private final TraceRowMapper rowMapper;

    @Override
    public EntityTraceSummaryDto buildEntityTraceSummary(ObservedEntityContext entityContext) {
        Map<String, Set<String>> identityValues = traceQueryIdentityValues(entityContext);
        if (identityValues.isEmpty()) {
            return new EntityTraceSummaryDto(0, 0, null, false, null);
        }
        long now = System.currentTimeMillis();
        if (traceQueryRepository.supportsTraceSummaryRows()) {
            Map<String, Object> row = traceQueryRepository.queryTraceSummaryRows(
                    Math.max(0L, now - DEFAULT_LOOKBACK_MILLIS),
                    now,
                    preferredIdentityValue(identityValues, "service.name"),
                    preferredIdentityValue(identityValues, "service.namespace"),
                    preferredIdentityValue(identityValues, "deployment.environment.name"),
                    AuthTokenRequestContext.currentWorkspaceId(),
                    identityValues,
                    false
            );
            EntityTraceSummaryDto summary = toEntityTraceSummary(row);
            if (summary != null) {
                return summary;
            }
        }
        List<TraceAggregate> traces = aggregateTraceRows(queryRecentRows(identityValues, now))
                .stream()
                .filter(trace -> matchesEntity(trace, identityValues))
                .filter(trace -> trace.getStartTime() == null || trace.getStartTime() >= now - DEFAULT_LOOKBACK_MILLIS)
                .toList();
        Long latestObservedAt = traces.stream()
                .map(TraceAggregate::getStartTime)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        int errorCount = (int) traces.stream().filter(trace -> isErrorStatus(trace.getStatus())).count();
        boolean active = latestObservedAt != null && latestObservedAt >= now - ACTIVE_TRACE_WINDOW_MILLIS;
        String latestTraceId = traces.stream()
                .sorted(Comparator.comparing(TraceAggregate::getStartTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(TraceAggregate::getTraceId)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        return new EntityTraceSummaryDto(traces.size(), errorCount, latestObservedAt, active, latestTraceId);
    }

    @Override
    public List<EntityTraceQueryHintDto> buildEntityTraceQueryHints(ObservedEntityContext entityContext) {
        Map<String, Set<String>> identityValues = traceQueryIdentityValues(entityContext);
        if (identityValues.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> resourceFilters = new LinkedHashMap<>();
        putPreferredFilter(resourceFilters, identityValues, "service.name");
        putPreferredFilter(resourceFilters, identityValues, "service.namespace");
        putPreferredFilter(resourceFilters, identityValues, "deployment.environment.name");
        putPreferredFilter(resourceFilters, identityValues, "host.name");
        putPreferredFilter(resourceFilters, identityValues, "k8s.namespace.name");

        List<String> searchTerms = new ArrayList<>();
        searchTerms.addAll(preferredSearchTerms(identityValues));
        EntityTraceSummaryDto summary = buildEntityTraceSummary(entityContext);
        if (StringUtils.hasText(summary.getLatestTraceId())) {
            searchTerms.add(summary.getLatestTraceId());
        }
        searchTerms = searchTerms.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        String entityTitle = resolveEntityTitle(entityContext);
        Long end = summary.getLatestObservedAt();
        Long start = end == null ? null : Math.max(0L, end - Duration.ofMinutes(15).toMillis());
        return List.of(new EntityTraceQueryHintDto(
                entityTitle + " trace evidence",
                resourceFilters,
                searchTerms,
                summary.getLatestTraceId(),
                null,
                resourceFilters.get("service.name"),
                resourceFilters.get("service.namespace"),
                resourceFilters.get("deployment.environment.name"),
                start,
                end
        ));
    }

    @Override
    public Page<TraceListItemDto> queryTraceList(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                                 String serviceName, String serviceNamespace, String environment,
                                                 int pageIndex, int pageSize, Boolean hideInternal) {
        return queryTraceList(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace, environment,
                null, null, null, pageIndex, pageSize, hideInternal);
    }

    @Override
    public Page<TraceListItemDto> queryTraceList(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                                 String serviceName, String serviceNamespace, String environment,
                                                 String operationName, Long minDurationMs, Long maxDurationMs,
                                                 int pageIndex, int pageSize, Boolean hideInternal) {
        return queryTraceList(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace, environment,
                null, operationName, minDurationMs, maxDurationMs, pageIndex, pageSize, hideInternal);
    }

    @Override
    public Page<TraceListItemDto> queryTraceList(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                                 String serviceName, String serviceNamespace, String environment,
                                                 String resourceFilter, String operationName, Long minDurationMs, Long maxDurationMs,
                                                 int pageIndex, int pageSize, Boolean hideInternal) {
        return queryTraceList(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace, environment,
                resourceFilter, operationName, minDurationMs, maxDurationMs, pageIndex, pageSize, hideInternal, null);
    }

    @Override
    public Page<TraceListItemDto> queryTraceList(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                                 String serviceName, String serviceNamespace, String environment,
                                                 String resourceFilter, String operationName, Long minDurationMs,
                                                 Long maxDurationMs, int pageIndex, int pageSize,
                                                 Boolean hideInternal, String spanScope) {
        return queryTraceList(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace, environment,
                resourceFilter, operationName, minDurationMs, maxDurationMs, pageIndex, pageSize, hideInternal,
                spanScope, null);
    }

    @Override
    public Page<TraceListItemDto> queryTraceList(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                                 String serviceName, String serviceNamespace, String environment,
                                                 String resourceFilter, String operationName, Long minDurationMs,
                                                 Long maxDurationMs, int pageIndex, int pageSize,
                                                 Boolean hideInternal, String spanScope, String attributeFilter) {
        ObservedEntityContext entityContext = entityId == null ? null : loadEntityContext(entityId);
        Map<String, Set<String>> identityValues = traceQueryIdentityValues(entityContext);
        TraceQueryScope queryScope = resolveTraceQueryScope(entityContext, identityValues, serviceName, serviceNamespace, environment);
        TraceResourceFilterParser.FilterSet resourceFilters = removeEntityScopeResourceFilters(
                identityValues, parseResourceFilters(resourceFilter));
        TraceResourceFilterParser.FilterSet attributeFilters = parseResourceFilters(attributeFilter);
        Map<String, Set<String>> pushedResourceFilters = mergeResourceFilters(identityValues, resourceFilters.pushableInclude());
        PageRequest pageRequest = PageRequest.of(normalizeTraceListPageIndex(pageIndex), normalizeTraceListPageSize(pageSize));
        int repositoryOffset = Math.toIntExact(Math.min(pageRequest.getOffset(), Integer.MAX_VALUE));
        Long minDurationNanos = durationMillisToNanos(minDurationMs);
        Long maxDurationNanos = durationMillisToNanos(maxDurationMs);
        String normalizedSpanScope = normalizeSpanScope(spanScope);
        if (!StringUtils.hasText(traceId) && !resourceFilters.requiresRowFallback()
                && attributeFilters.isEmpty()
                && traceQueryRepository.supportsTraceListRows()) {
            List<Map<String, Object>> rows = StringUtils.hasText(normalizedSpanScope)
                    ? traceQueryRepository.queryTraceListRows(
                            start,
                            end,
                            errorOnly,
                            queryScope.serviceName(),
                            queryScope.serviceNamespace(),
                            queryScope.environment(),
                            operationName,
                            minDurationNanos,
                            maxDurationNanos,
                            AuthTokenRequestContext.currentWorkspaceId(),
                            pushedResourceFilters,
                            hideInternal,
                            normalizedSpanScope,
                            repositoryOffset,
                            pageRequest.getPageSize()
                    )
                    : traceQueryRepository.queryTraceListRows(
                            start,
                            end,
                            errorOnly,
                            queryScope.serviceName(),
                            queryScope.serviceNamespace(),
                            queryScope.environment(),
                            operationName,
                            minDurationNanos,
                            maxDurationNanos,
                            AuthTokenRequestContext.currentWorkspaceId(),
                            pushedResourceFilters,
                            hideInternal,
                            repositoryOffset,
                            pageRequest.getPageSize()
                    );
            List<TraceListItemDto> items = rows.stream()
                    .map(this::toTraceListItem)
                    .toList();
            long total = rows.stream()
                    .map(row -> readLongValue(row, "total_count", "totalCount"))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse((long) pageRequest.getOffset() + items.size());
            return new PageImpl<>(items, pageRequest, total);
        }
        List<TraceAggregate> filtered = aggregateTraceRows(queryRowsForList(traceId, start, end, queryScope.serviceName(),
                queryScope.serviceNamespace(), queryScope.environment(), operationName, minDurationNanos,
                maxDurationNanos, pushedResourceFilters, hideInternal)).stream()
                .filter(trace -> matchesSpanScope(trace, normalizedSpanScope))
                .filter(trace -> matchesTraceFilters(trace, identityValues, resourceFilters, start, end, traceId, errorOnly,
                        queryScope.serviceName(), queryScope.serviceNamespace(), queryScope.environment(), operationName,
                        minDurationNanos, maxDurationNanos, hideInternal, attributeFilters))
                .sorted(Comparator.comparing(TraceAggregate::getStartTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int safeStart = Math.min(repositoryOffset, filtered.size());
        int safeEnd = Math.min(safeStart + pageRequest.getPageSize(), filtered.size());
        List<TraceListItemDto> items = filtered.subList(safeStart, safeEnd).stream()
                .map(this::toTraceListItem)
                .toList();
        return new PageImpl<>(items, pageRequest, filtered.size());
    }

    private int normalizeTraceListPageIndex(int pageIndex) {
        return Math.max(pageIndex, DEFAULT_TRACE_LIST_PAGE_INDEX);
    }

    private int normalizeTraceListPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_TRACE_LIST_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_TRACE_LIST_PAGE_SIZE);
    }

    @Override
    public TraceDetailDto getTraceDetail(Long entityId, String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return null;
        }
        Map<String, Set<String>> identityValues = entityId == null ? Collections.emptyMap() : canonicalIdentityValues(loadEntityContext(entityId));
        TraceAggregate aggregate = aggregateTraceRows(queryTraceRows(traceId, null, null, null, null, null,
                identityValues, false)).stream()
                .filter(trace -> identityValues.isEmpty() || matchesEntity(trace, identityValues))
                .findFirst()
                .orElse(null);
        return aggregate == null ? null : toTraceDetail(aggregate);
    }

    @Override
    public List<TraceSpanNodeDto> getTraceSpans(Long entityId, String traceId) {
        TraceDetailDto detail = getTraceDetail(entityId, traceId);
        return detail == null ? Collections.emptyList() : detail.getSpans();
    }

    @Override
    public TraceOverviewDto getTraceOverview(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                             String serviceName, String serviceNamespace, String environment, Boolean hideInternal) {
        return getTraceOverview(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace, environment,
                null, null, null, hideInternal);
    }

    @Override
    public TraceOverviewDto getTraceOverview(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                             String serviceName, String serviceNamespace, String environment,
                                             String operationName, Long minDurationMs, Long maxDurationMs, Boolean hideInternal) {
        return getTraceOverview(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace, environment,
                null, operationName, minDurationMs, maxDurationMs, hideInternal);
    }

    @Override
    public TraceOverviewDto getTraceOverview(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                             String serviceName, String serviceNamespace, String environment,
                                             String resourceFilter, String operationName, Long minDurationMs, Long maxDurationMs,
                                             Boolean hideInternal) {
        return getTraceOverview(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace, environment,
                resourceFilter, operationName, minDurationMs, maxDurationMs, hideInternal, null);
    }

    @Override
    public TraceOverviewDto getTraceOverview(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                             String serviceName, String serviceNamespace, String environment,
                                             String resourceFilter, String operationName, Long minDurationMs, Long maxDurationMs,
                                             Boolean hideInternal, String spanScope) {
        return getTraceOverview(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace, environment,
                resourceFilter, operationName, minDurationMs, maxDurationMs, hideInternal, spanScope, null);
    }

    @Override
    public TraceOverviewDto getTraceOverview(Long entityId, Long start, Long end, String traceId, Boolean errorOnly,
                                             String serviceName, String serviceNamespace, String environment,
                                             String resourceFilter, String operationName, Long minDurationMs, Long maxDurationMs,
                                             Boolean hideInternal, String spanScope, String attributeFilter) {
        ObservedEntityContext entityContext = entityId == null ? null : loadEntityContext(entityId);
        Map<String, Set<String>> identityValues = traceQueryIdentityValues(entityContext);
        TraceQueryScope queryScope = resolveTraceQueryScope(entityContext, identityValues, serviceName, serviceNamespace, environment);
        TraceResourceFilterParser.FilterSet resourceFilters = removeEntityScopeResourceFilters(
                identityValues, parseResourceFilters(resourceFilter));
        TraceResourceFilterParser.FilterSet attributeFilters = parseResourceFilters(attributeFilter);
        Map<String, Set<String>> pushedResourceFilters = mergeResourceFilters(identityValues, resourceFilters.pushableInclude());
        Long minDurationNanos = durationMillisToNanos(minDurationMs);
        Long maxDurationNanos = durationMillisToNanos(maxDurationMs);
        String normalizedSpanScope = normalizeSpanScope(spanScope);
        if (StringUtils.hasText(traceId) && !resourceFilters.requiresRowFallback()
                && attributeFilters.isEmpty()
                && traceQueryRepository.supportsTraceIdOverviewRows()) {
            Map<String, Object> row = StringUtils.hasText(normalizedSpanScope)
                    ? traceQueryRepository.queryTraceIdOverviewRows(
                            traceId,
                            start,
                            end,
                            errorOnly,
                            queryScope.serviceName(),
                            queryScope.serviceNamespace(),
                            queryScope.environment(),
                            operationName,
                            minDurationNanos,
                            maxDurationNanos,
                            AuthTokenRequestContext.currentWorkspaceId(),
                            pushedResourceFilters,
                            hideInternal,
                            normalizedSpanScope
                    )
                    : traceQueryRepository.queryTraceIdOverviewRows(
                            traceId,
                            start,
                            end,
                            errorOnly,
                            queryScope.serviceName(),
                            queryScope.serviceNamespace(),
                            queryScope.environment(),
                            operationName,
                            minDurationNanos,
                            maxDurationNanos,
                            AuthTokenRequestContext.currentWorkspaceId(),
                            pushedResourceFilters,
                            hideInternal
                    );
            TraceOverviewDto overview = toTraceOverview(row);
            if (overview != null) {
                return overview;
            }
        }
        if (!StringUtils.hasText(traceId) && !resourceFilters.requiresRowFallback()
                && attributeFilters.isEmpty()
                && traceQueryRepository.supportsTraceOverviewRows()) {
            Map<String, Object> row = StringUtils.hasText(normalizedSpanScope)
                    ? traceQueryRepository.queryTraceOverviewRows(
                            start,
                            end,
                            errorOnly,
                            queryScope.serviceName(),
                            queryScope.serviceNamespace(),
                            queryScope.environment(),
                            operationName,
                            minDurationNanos,
                            maxDurationNanos,
                            AuthTokenRequestContext.currentWorkspaceId(),
                            pushedResourceFilters,
                            hideInternal,
                            normalizedSpanScope
                    )
                    : traceQueryRepository.queryTraceOverviewRows(
                            start,
                            end,
                            errorOnly,
                            queryScope.serviceName(),
                            queryScope.serviceNamespace(),
                            queryScope.environment(),
                            operationName,
                            minDurationNanos,
                            maxDurationNanos,
                            AuthTokenRequestContext.currentWorkspaceId(),
                            pushedResourceFilters,
                            hideInternal
                    );
            TraceOverviewDto overview = toTraceOverview(row);
            if (overview != null) {
                return overview;
            }
        }
        Page<TraceListItemDto> result = queryTraceList(entityId, start, end, traceId, errorOnly,
                queryScope.serviceName(), queryScope.serviceNamespace(), queryScope.environment(),
                resourceFilter, operationName, minDurationMs, maxDurationMs, 0, TRACE_LIST_SAMPLE_LIMIT, hideInternal,
                normalizedSpanScope, attributeFilter);
        Long latestObservedAt = result.getContent().stream()
                .map(TraceListItemDto::getStartTime)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        int errorTraceCount = (int) result.getContent().stream().filter(item -> isErrorStatus(item.getStatus())).count();
        boolean active = latestObservedAt != null && latestObservedAt >= System.currentTimeMillis() - ACTIVE_TRACE_WINDOW_MILLIS;
        return new TraceOverviewDto((int) result.getTotalElements(), errorTraceCount, latestObservedAt, active);
    }

    @Override
    public Map<String, Object> getTraceGroupByStats(Long entityId, Long start, Long end, String traceId,
                                                    Boolean errorOnly, String serviceName, String serviceNamespace,
                                                    String environment, String resourceFilter, String operationName,
                                                    Long minDurationMs, Long maxDurationMs, String groupBy,
                                                    Integer limit, String orderBy, Integer minCount, Boolean hideInternal) {
        return getTraceGroupByStats(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace,
                environment, resourceFilter, operationName, minDurationMs, maxDurationMs, groupBy, limit, orderBy,
                minCount, hideInternal, null);
    }

    @Override
    public Map<String, Object> getTraceGroupByStats(Long entityId, Long start, Long end, String traceId,
                                                    Boolean errorOnly, String serviceName, String serviceNamespace,
                                                    String environment, String resourceFilter, String operationName,
                                                    Long minDurationMs, Long maxDurationMs, String groupBy,
                                                    Integer limit, String orderBy, Integer minCount, Boolean hideInternal,
                                                    String spanScope) {
        return getTraceGroupByStats(entityId, start, end, traceId, errorOnly, serviceName, serviceNamespace,
                environment, resourceFilter, operationName, minDurationMs, maxDurationMs, groupBy, limit, orderBy,
                minCount, hideInternal, spanScope, null);
    }

    @Override
    public Map<String, Object> getTraceGroupByStats(Long entityId, Long start, Long end, String traceId,
                                                    Boolean errorOnly, String serviceName, String serviceNamespace,
                                                    String environment, String resourceFilter, String operationName,
                                                    Long minDurationMs, Long maxDurationMs, String groupBy,
                                                    Integer limit, String orderBy, Integer minCount, Boolean hideInternal,
                                                    String spanScope, String attributeFilter) {
        String normalizedGroupBy = normalizeTraceGroupBy(groupBy);
        int resolvedLimit = resolveTraceGroupByLimit(limit);
        long resolvedMinCount = resolveTraceGroupByMinCount(minCount);
        String normalizedSpanScope = normalizeSpanScope(spanScope);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groupBy", normalizedGroupBy == null ? trimText(groupBy) : normalizedGroupBy);
        if (!StringUtils.hasText(normalizedGroupBy)) {
            result.put("groups", List.of());
            return result;
        }
        ObservedEntityContext entityContext = entityId == null ? null : loadEntityContext(entityId);
        Map<String, Set<String>> identityValues = traceQueryIdentityValues(entityContext);
        TraceQueryScope queryScope = resolveTraceQueryScope(entityContext, identityValues, serviceName, serviceNamespace, environment);
        TraceResourceFilterParser.FilterSet resourceFilters = removeEntityScopeResourceFilters(
                identityValues, parseResourceFilters(resourceFilter));
        TraceResourceFilterParser.FilterSet attributeFilters = parseResourceFilters(attributeFilter);
        Map<String, Set<String>> pushedResourceFilters = mergeResourceFilters(identityValues, resourceFilters.pushableInclude());
        Long minDurationNanos = durationMillisToNanos(minDurationMs);
        Long maxDurationNanos = durationMillisToNanos(maxDurationMs);
        if (!StringUtils.hasText(traceId) && !resourceFilters.requiresRowFallback()
                && attributeFilters.isEmpty()
                && !isTraceAttributeGroupBy(normalizedGroupBy)
                && traceQueryRepository.supportsTraceGroupByRows()) {
            List<Map<String, Object>> rows = StringUtils.hasText(normalizedSpanScope)
                    ? traceQueryRepository.queryTraceGroupByRows(
                            start,
                            end,
                            errorOnly,
                            queryScope.serviceName(),
                            queryScope.serviceNamespace(),
                            queryScope.environment(),
                            operationName,
                            minDurationNanos,
                            maxDurationNanos,
                            AuthTokenRequestContext.currentWorkspaceId(),
                            pushedResourceFilters,
                            hideInternal,
                            normalizedSpanScope,
                            normalizedGroupBy,
                            orderBy,
                            resolvedMinCount,
                            resolvedLimit
                    )
                    : traceQueryRepository.queryTraceGroupByRows(
                            start,
                            end,
                            errorOnly,
                            queryScope.serviceName(),
                            queryScope.serviceNamespace(),
                            queryScope.environment(),
                            operationName,
                            minDurationNanos,
                            maxDurationNanos,
                            AuthTokenRequestContext.currentWorkspaceId(),
                            pushedResourceFilters,
                            hideInternal,
                            normalizedGroupBy,
                            orderBy,
                            resolvedMinCount,
                            resolvedLimit
                    );
            result.put("groups", rows.stream().map(this::toTraceGroupResult).toList());
            return result;
        }
        List<TraceAggregate> traces = aggregateTraceRows(queryRowsForList(traceId, start, end, queryScope.serviceName(),
                queryScope.serviceNamespace(), queryScope.environment(), operationName, minDurationNanos,
                maxDurationNanos, pushedResourceFilters, hideInternal)).stream()
                .filter(trace -> matchesSpanScope(trace, normalizedSpanScope))
                .filter(trace -> matchesTraceFilters(trace, identityValues, resourceFilters, start, end, traceId, errorOnly,
                        queryScope.serviceName(), queryScope.serviceNamespace(), queryScope.environment(), operationName,
                        minDurationNanos, maxDurationNanos, hideInternal, attributeFilters))
                .toList();
        result.put("groups", buildTraceAggregateGroupResults(traces, normalizedGroupBy, resolvedLimit, orderBy, resolvedMinCount));
        return result;
    }

    private Map<String, Object> toTraceGroupResult(Map<String, Object> row) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("value", defaultText(readTextValue(row, "group_value"), "unknown"));
        group.put("traceCount", Optional.ofNullable(readLongValue(row, "trace_count", "traceCount")).orElse(0L));
        group.put("errorTraceCount", Optional.ofNullable(readLongValue(row, "error_trace_count", "errorTraceCount")).orElse(0L));
        group.put("latencyAvgMs", Optional.ofNullable(readDoubleValue(row, "latency_avg_ms", "latencyAvgMs")).orElse(0.0d));
        group.put("latencyP95Ms", Optional.ofNullable(readDoubleValue(row, "latency_p95_ms", "latencyP95Ms")).orElse(0.0d));
        return group;
    }

    private List<Map<String, Object>> buildTraceGroupResults(List<TraceListItemDto> traces, String groupBy, int limit, String orderBy, long minCount) {
        if (CollectionUtils.isEmpty(traces)) {
            return List.of();
        }
        Map<String, List<TraceListItemDto>> grouped = new LinkedHashMap<>();
        for (TraceListItemDto trace : traces) {
            String value = defaultText(resolveTraceListGroupValue(trace, groupBy), "unknown");
            grouped.computeIfAbsent(value, ignored -> new ArrayList<>()).add(trace);
        }
        return grouped.entrySet().stream()
                .map(entry -> toTraceGroupResult(entry.getKey(), entry.getValue()))
                .filter(group -> ((Long) group.get("traceCount")) >= minCount)
                .sorted(resolveTraceGroupComparator(orderBy))
                .limit(limit)
                .toList();
    }

    private List<Map<String, Object>> buildTraceAggregateGroupResults(List<TraceAggregate> traces, String groupBy,
                                                                      int limit, String orderBy, long minCount) {
        if (CollectionUtils.isEmpty(traces)) {
            return List.of();
        }
        Map<String, List<TraceAggregate>> grouped = new LinkedHashMap<>();
        for (TraceAggregate trace : traces) {
            String value = defaultText(resolveTraceAggregateGroupValue(trace, groupBy), "unknown");
            grouped.computeIfAbsent(value, ignored -> new ArrayList<>()).add(trace);
        }
        return grouped.entrySet().stream()
                .map(entry -> toTraceAggregateGroupResult(entry.getKey(), entry.getValue()))
                .filter(group -> ((Long) group.get("traceCount")) >= minCount)
                .sorted(resolveTraceGroupComparator(orderBy))
                .limit(limit)
                .toList();
    }

    private Comparator<Map<String, Object>> resolveTraceGroupComparator(String orderBy) {
        String normalized = StringUtils.trimWhitespace(orderBy);
        if ("error-count-desc".equalsIgnoreCase(normalized)) {
            return (left, right) -> Long.compare((Long) right.get("errorTraceCount"), (Long) left.get("errorTraceCount"));
        }
        if ("latency-p95-desc".equalsIgnoreCase(normalized)) {
            return (left, right) -> Double.compare((Double) right.get("latencyP95Ms"), (Double) left.get("latencyP95Ms"));
        }
        return (left, right) -> Long.compare((Long) right.get("traceCount"), (Long) left.get("traceCount"));
    }

    private int resolveTraceGroupByLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return TRACE_GROUP_BY_LIMIT;
        }
        return Math.min(limit, TRACE_GROUP_BY_MAX_LIMIT);
    }

    private long resolveTraceGroupByMinCount(Integer minCount) {
        if (minCount == null || minCount < 1) {
            return 1L;
        }
        return Math.min(minCount.longValue(), TRACE_GROUP_BY_MAX_MIN_COUNT);
    }

    private Map<String, Object> toTraceGroupResult(String value, List<TraceListItemDto> traces) {
        Map<String, Object> group = new LinkedHashMap<>();
        List<Long> durations = traces.stream()
                .map(TraceListItemDto::getDurationNanos)
                .filter(Objects::nonNull)
                .filter(duration -> duration >= 0)
                .sorted()
                .toList();
        group.put("value", value);
        group.put("traceCount", (long) traces.size());
        group.put("errorTraceCount", traces.stream().filter(trace -> isErrorStatus(trace.getStatus())).count());
        group.put("latencyAvgMs", durations.isEmpty() ? 0.0d
                : durations.stream().mapToDouble(Long::doubleValue).average().orElse(0.0d) / 1_000_000.0d);
        group.put("latencyP95Ms", durations.isEmpty() ? 0.0d
                : durations.get(Math.min(durations.size() - 1, (int) Math.ceil(durations.size() * 0.95d) - 1)) / 1_000_000.0d);
        return group;
    }

    private Map<String, Object> toTraceAggregateGroupResult(String value, List<TraceAggregate> traces) {
        Map<String, Object> group = new LinkedHashMap<>();
        List<Long> durations = traces.stream()
                .map(TraceAggregate::getDurationNanos)
                .filter(Objects::nonNull)
                .filter(duration -> duration >= 0)
                .sorted()
                .toList();
        group.put("value", value);
        group.put("traceCount", (long) traces.size());
        group.put("errorTraceCount", traces.stream().filter(trace -> isErrorStatus(trace.getStatus())).count());
        group.put("latencyAvgMs", durations.isEmpty() ? 0.0d
                : durations.stream().mapToDouble(Long::doubleValue).average().orElse(0.0d) / 1_000_000.0d);
        group.put("latencyP95Ms", durations.isEmpty() ? 0.0d
                : durations.get(Math.min(durations.size() - 1, (int) Math.ceil(durations.size() * 0.95d) - 1)) / 1_000_000.0d);
        return group;
    }

    private String resolveTraceListGroupValue(TraceListItemDto trace, String groupBy) {
        if (trace == null || !StringUtils.hasText(groupBy)) {
            return null;
        }
        Map<String, String> resourceAttributes = trace.getResourceAttributes() == null
                ? Collections.emptyMap()
                : trace.getResourceAttributes();
        if ("service.name".equals(groupBy)) {
            return defaultText(trace.getServiceName(), resourceAttributes.get("service.name"));
        }
        if ("operation.name".equals(groupBy)) {
            return trace.getRootSpanName();
        }
        if ("status".equals(groupBy)) {
            return isErrorStatus(trace.getStatus()) ? "ERROR" : "OK";
        }
        if (groupBy.startsWith("resource:")) {
            return resourceAttributes.get(groupBy.substring("resource:".length()));
        }
        return resourceAttributes.get(groupBy);
    }

    private String resolveTraceAggregateGroupValue(TraceAggregate trace, String groupBy) {
        if (trace == null || !StringUtils.hasText(groupBy)) {
            return null;
        }
        Map<String, String> resourceAttributes = trace.getResourceAttributes() == null
                ? Collections.emptyMap()
                : trace.getResourceAttributes();
        if ("service.name".equals(groupBy)) {
            return defaultText(trace.getServiceName(), resourceAttributes.get("service.name"));
        }
        if ("operation.name".equals(groupBy)) {
            return trace.getRootSpanName();
        }
        if ("status".equals(groupBy)) {
            return isErrorStatus(trace.getStatus()) ? "ERROR" : "OK";
        }
        if (groupBy.startsWith("resource:")) {
            return resourceAttributes.get(groupBy.substring("resource:".length()));
        }
        if (groupBy.startsWith("attribute:")) {
            String key = groupBy.substring("attribute:".length());
            return trace.spans.stream()
                    .map(TraceSpanNodeDto::getSpanAttributes)
                    .filter(attributes -> !CollectionUtils.isEmpty(attributes))
                    .map(attributes -> attributes.get(key))
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
        }
        return resourceAttributes.get(groupBy);
    }

    private String normalizeTraceGroupBy(String groupBy) {
        if (!StringUtils.hasText(groupBy)) {
            return null;
        }
        String normalized = groupBy.trim().toLowerCase(Locale.ROOT);
        if ("service_name".equals(normalized)) {
            return "service.name";
        }
        if ("operation".equals(normalized) || "operation.name".equals(normalized)
                || "span.name".equals(normalized) || "span_name".equals(normalized)) {
            return "operation.name";
        }
        if ("status".equals(normalized) || "error".equals(normalized)) {
            return "status";
        }
        if (normalized.startsWith("resource:")) {
            String key = normalized.substring("resource:".length());
            return resourceFilterParser.isSafeKey(key) ? "resource:" + key : null;
        }
        if (normalized.startsWith("attribute:")) {
            String key = normalized.substring("attribute:".length());
            return resourceFilterParser.isSafeKey(key) ? "attribute:" + key : null;
        }
        return resourceFilterParser.isSafeKey(normalized) ? normalized : null;
    }

    private boolean isTraceAttributeGroupBy(String groupBy) {
        return StringUtils.hasText(groupBy) && groupBy.startsWith("attribute:");
    }

    private TraceOverviewDto toTraceOverview(Map<String, Object> row) {
        if (CollectionUtils.isEmpty(row)) {
            return null;
        }
        int totalTraceCount = Optional.ofNullable(readIntValue(row, "total_trace_count", "totalTraceCount"))
                .orElse(0);
        int errorTraceCount = Optional.ofNullable(readIntValue(row, "error_trace_count", "errorTraceCount"))
                .orElse(0);
        Long latestObservedAt = readTimestamp(row, "latest_observed_at");
        if (latestObservedAt == null) {
            latestObservedAt = readTimestamp(row, "latestObservedAt");
        }
        boolean active = latestObservedAt != null
                && latestObservedAt >= System.currentTimeMillis() - ACTIVE_TRACE_WINDOW_MILLIS;
        return new TraceOverviewDto(totalTraceCount, errorTraceCount, latestObservedAt, active);
    }

    private EntityTraceSummaryDto toEntityTraceSummary(Map<String, Object> row) {
        if (CollectionUtils.isEmpty(row)) {
            return null;
        }
        int totalTraceCount = Optional.ofNullable(readIntValue(row, "total_trace_count", "totalTraceCount"))
                .orElse(0);
        int errorTraceCount = Optional.ofNullable(readIntValue(row, "error_trace_count", "errorTraceCount"))
                .orElse(0);
        Long latestObservedAt = readTimestamp(row, "latest_observed_at");
        if (latestObservedAt == null) {
            latestObservedAt = readTimestamp(row, "latestObservedAt");
        }
        String latestTraceId = defaultText(readTextValue(row, "latest_trace_id"),
                readTextValue(row, "latestTraceId"));
        boolean active = latestObservedAt != null
                && latestObservedAt >= System.currentTimeMillis() - ACTIVE_TRACE_WINDOW_MILLIS;
        return new EntityTraceSummaryDto(totalTraceCount, errorTraceCount, latestObservedAt, active, latestTraceId);
    }

    private ObservedEntityContext loadEntityContext(Long entityId) {
        if (entityId == null || entityId <= 0) {
            return null;
        }
        Optional<ObserveEntity> entityOptional = workspaceQueryGateway.findEntityById(entityId);
        if (entityOptional.isEmpty()) {
            return null;
        }
        return ObservedEntityContext.from(entityOptional.get(), workspaceQueryGateway.findIdentitiesByEntityId(entityId));
    }

    private TraceQueryScope resolveTraceQueryScope(ObservedEntityContext entityContext,
                                                   Map<String, Set<String>> identityValues,
                                                   String serviceName,
                                                   String serviceNamespace,
                                                   String environment) {
        return new TraceQueryScope(
                defaultText(preferredIdentityValue(identityValues, "service.name"),
                        fallbackServiceName(entityContext, serviceName)),
                defaultText(preferredIdentityValue(identityValues, "service.namespace"), serviceNamespace),
                defaultText(preferredIdentityValue(identityValues, "deployment.environment.name"), environment)
        );
    }

    private String fallbackServiceName(ObservedEntityContext entityContext, String serviceName) {
        if (StringUtils.hasText(serviceName)) {
            return serviceName;
        }
        if (entityContext == null || entityContext.getEntity() == null
                || !"service".equalsIgnoreCase(trimText(entityContext.getEntity().getType()))) {
            return null;
        }
        return trimText(entityContext.getEntity().getName());
    }

    private List<Map<String, Object>> queryRecentRows() {
        return traceQueryRepository.queryRecentTraceRows(TRACE_LIST_SAMPLE_LIMIT);
    }

    private List<Map<String, Object>> queryRecentRows(Map<String, Set<String>> identityValues, long now) {
        if (CollectionUtils.isEmpty(identityValues)) {
            return queryRecentRows();
        }
        return traceQueryRepository.queryRecentTraceRows(
                TRACE_LIST_SAMPLE_LIMIT,
                Math.max(0L, now - DEFAULT_LOOKBACK_MILLIS),
                now,
                preferredIdentityValue(identityValues, "service.name"),
                preferredIdentityValue(identityValues, "service.namespace"),
                preferredIdentityValue(identityValues, "deployment.environment.name"),
                AuthTokenRequestContext.currentWorkspaceId(),
                identityValues,
                false
        );
    }

    private List<Map<String, Object>> queryRowsForList(String traceId,
                                                       Long start,
                                                       Long end,
                                                       String serviceName,
                                                       String serviceNamespace,
                                                       String environment,
                                                       String operationName,
                                                       Long minDurationNanos,
                                                       Long maxDurationNanos,
                                                       Map<String, Set<String>> identityValues,
                                                       Boolean hideInternal) {
        if (StringUtils.hasText(traceId)) {
            return queryTraceRows(traceId, start, end, serviceName, serviceNamespace, environment,
                    operationName, minDurationNanos, maxDurationNanos,
                    identityValues, hideInternal);
        }
        return traceQueryRepository.queryRecentTraceRows(
                TRACE_LIST_SAMPLE_LIMIT,
                start,
                end,
                serviceName,
                serviceNamespace,
                environment,
                operationName,
                minDurationNanos,
                maxDurationNanos,
                AuthTokenRequestContext.currentWorkspaceId(),
                identityValues,
                hideInternal
        );
    }

    private List<Map<String, Object>> queryTraceRows(String traceId) {
        return queryTraceRows(traceId, null, null, null, null, null, Collections.emptyMap(), false);
    }

    private List<Map<String, Object>> queryTraceRows(String traceId,
                                                     Long start,
                                                     Long end,
                                                     String serviceName,
                                                     String serviceNamespace,
                                                     String environment,
                                                     Map<String, Set<String>> identityValues,
                                                     Boolean hideInternal) {
        return queryTraceRows(traceId, start, end, serviceName, serviceNamespace, environment,
                null, null, null, identityValues, hideInternal);
    }

    private List<Map<String, Object>> queryTraceRows(String traceId,
                                                     Long start,
                                                     Long end,
                                                     String serviceName,
                                                     String serviceNamespace,
                                                     String environment,
                                                     String operationName,
                                                     Long minDurationNanos,
                                                     Long maxDurationNanos,
                                                     Map<String, Set<String>> identityValues,
                                                     Boolean hideInternal) {
        String workspaceId = AuthTokenRequestContext.currentWorkspaceId();
        if (!hasTraceRowPushdownFilters(start, end, serviceName, serviceNamespace, environment,
                operationName, minDurationNanos, maxDurationNanos, workspaceId, identityValues, hideInternal)) {
            return traceQueryRepository.queryTraceRows(traceId, TRACE_DETAIL_LIMIT);
        }
        return traceQueryRepository.queryTraceRows(
                traceId,
                TRACE_DETAIL_LIMIT,
                start,
                end,
                serviceName,
                serviceNamespace,
                environment,
                operationName,
                minDurationNanos,
                maxDurationNanos,
                workspaceId,
                identityValues,
                hideInternal
        );
    }

    private boolean hasTraceRowPushdownFilters(Long start,
                                               Long end,
                                               String serviceName,
                                               String serviceNamespace,
                                               String environment,
                                               String operationName,
                                               Long minDurationNanos,
                                               Long maxDurationNanos,
                                               String workspaceId,
                                               Map<String, Set<String>> identityValues,
                                               Boolean hideInternal) {
        return start != null
                || end != null
                || StringUtils.hasText(serviceName)
                || StringUtils.hasText(serviceNamespace)
                || StringUtils.hasText(environment)
                || StringUtils.hasText(operationName)
                || minDurationNanos != null
                || maxDurationNanos != null
                || StringUtils.hasText(workspaceId)
                || !CollectionUtils.isEmpty(identityValues)
                || Boolean.TRUE.equals(hideInternal);
    }

    private List<TraceAggregate> aggregateTraceRows(List<Map<String, Object>> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        Map<String, TraceAggregate> traceMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            TraceSpanNodeDto span = toSpanNode(row);
            if (!StringUtils.hasText(span.getTraceId())) {
                continue;
            }
            if (!matchesRequestWorkspace(span)) {
                continue;
            }
            TraceAggregate aggregate = traceMap.computeIfAbsent(span.getTraceId(), TraceAggregate::new);
            aggregate.accept(span);
        }
        return traceMap.values().stream().map(TraceAggregate::normalize).toList();
    }

    private boolean matchesRequestWorkspace(TraceSpanNodeDto span) {
        String workspaceId = AuthTokenRequestContext.currentWorkspaceId();
        if (!StringUtils.hasText(workspaceId)) {
            return true;
        }
        String spanWorkspaceId = resolveWorkspaceId(span);
        String normalizedWorkspaceId = AuthTokenScopes.normalizeWorkspaceId(workspaceId);
        if (!StringUtils.hasText(spanWorkspaceId)) {
            return AuthTokenScopes.DEFAULT_WORKSPACE_ID.equals(normalizedWorkspaceId);
        }
        return normalizedWorkspaceId.equals(AuthTokenScopes.normalizeWorkspaceId(spanWorkspaceId));
    }

    private String resolveWorkspaceId(TraceSpanNodeDto span) {
        if (span == null || CollectionUtils.isEmpty(span.getResourceAttributes())) {
            return null;
        }
        for (String key : WORKSPACE_RESOURCE_KEYS) {
            String value = trimText(span.getResourceAttributes().get(key));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean matchesTraceFilters(TraceAggregate trace, Map<String, Set<String>> identityValues,
                                        TraceResourceFilterParser.FilterSet resourceFilters, Long start, Long end,
                                        String traceId, Boolean errorOnly, String serviceName, String serviceNamespace,
                                        String environment, String operationName, Long minDurationNanos,
                                        Long maxDurationNanos, Boolean hideInternal,
                                        TraceResourceFilterParser.FilterSet attributeFilters) {
        if (trace == null) {
            return false;
        }
        if (Boolean.TRUE.equals(hideInternal) && isSelfTelemetryTrace(trace)) {
            return false;
        }
        if (StringUtils.hasText(traceId) && !traceId.equalsIgnoreCase(trace.getTraceId())) {
            return false;
        }
        Long startTime = trace.getStartTime();
        if (start != null && startTime != null && startTime < start) {
            return false;
        }
        if (end != null && startTime != null && startTime > end) {
            return false;
        }
        if (Boolean.TRUE.equals(errorOnly) && !isErrorStatus(trace.getStatus())) {
            return false;
        }
        if (StringUtils.hasText(serviceName) && !serviceName.equalsIgnoreCase(defaultText(trace.getServiceName(),
                trace.getResourceAttributes().get("service.name")))) {
            return false;
        }
        if (StringUtils.hasText(serviceNamespace) && !serviceNamespace.equalsIgnoreCase(trace.getServiceNamespace())) {
            return false;
        }
        if (StringUtils.hasText(environment)
                && !environment.equalsIgnoreCase(trace.getResourceAttributes().get("deployment.environment.name"))) {
            return false;
        }
        if (!matchesTraceOperation(trace, operationName)) {
            return false;
        }
        if (minDurationNanos != null && (trace.getDurationNanos() == null || trace.getDurationNanos() < minDurationNanos)) {
            return false;
        }
        if (maxDurationNanos != null && (trace.getDurationNanos() == null || trace.getDurationNanos() > maxDurationNanos)) {
            return false;
        }
        if (!identityValues.isEmpty() && !matchesEntity(trace, identityValues)) {
            return false;
        }
        return (resourceFilters.isEmpty() || matchesResourceFilters(trace, resourceFilters))
                && matchesSpanAttributeFilters(trace, attributeFilters);
    }

    private boolean matchesTraceOperation(TraceAggregate trace, String operationName) {
        String normalizedOperationName = StringUtils.trimWhitespace(operationName);
        if (!StringUtils.hasText(normalizedOperationName)) {
            return true;
        }
        if (normalizedOperationName.equalsIgnoreCase(trace.getRootSpanName())) {
            return true;
        }
        return trace.spans.stream()
                .map(TraceSpanNodeDto::getSpanName)
                .filter(StringUtils::hasText)
                .anyMatch(normalizedOperationName::equalsIgnoreCase);
    }

    private String normalizeSpanScope(String spanScope) {
        String normalized = StringUtils.trimWhitespace(spanScope);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if ("root".equals(normalized)) {
            return "root";
        }
        if ("entrypoint".equals(normalized) || "entrypoint-spans".equals(normalized) || "entry".equals(normalized)) {
            return "entrypoint";
        }
        return null;
    }

    private boolean matchesSpanScope(TraceAggregate trace, String spanScope) {
        if (!StringUtils.hasText(spanScope) || trace == null) {
            return true;
        }
        if ("root".equals(spanScope)) {
            return trace.spans.stream().anyMatch(span -> !StringUtils.hasText(span.getParentSpanId()));
        }
        if ("entrypoint".equals(spanScope)) {
            return trace.spans.stream().anyMatch(span -> !StringUtils.hasText(span.getParentSpanId())
                    || isEntrypointSpanKind(span.getSpanKind()));
        }
        return true;
    }

    private boolean isEntrypointSpanKind(String spanKind) {
        String normalized = StringUtils.trimWhitespace(spanKind);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return "SPAN_KIND_SERVER".equals(normalized)
                || "SERVER".equals(normalized)
                || "SPAN_KIND_CONSUMER".equals(normalized)
                || "CONSUMER".equals(normalized);
    }

    private Long durationMillisToNanos(Long durationMillis) {
        if (durationMillis == null || durationMillis < 0) {
            return null;
        }
        if (durationMillis > Long.MAX_VALUE / 1_000_000L) {
            return Long.MAX_VALUE;
        }
        return durationMillis * 1_000_000L;
    }

    private boolean isSelfTelemetryTrace(TraceAggregate trace) {
        if (trace == null) {
            return false;
        }
        String serviceName = normalizeValue(defaultText(trace.getServiceName(), trace.getResourceAttributes().get("service.name")));
        String serviceNamespace = normalizeValue(trace.getResourceAttributes().get("service.namespace"));
        return "hertzbeat".equals(serviceName)
                || "apache-hertzbeat".equals(serviceName)
                || "hertzbeat".equals(serviceNamespace)
                || "apache-hertzbeat".equals(serviceNamespace);
    }

    private String normalizeValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private boolean matchesEntity(TraceAggregate trace, Map<String, Set<String>> identityValues) {
        if (identityValues.isEmpty() || trace == null) {
            return false;
        }
        for (Map.Entry<String, Set<String>> entry : identityValues.entrySet()) {
            String actual = trimText(resolveCanonicalValue(trace.getResourceAttributes(), entry.getKey(), trace.getServiceName()));
            if (actual == null) {
                continue;
            }
            for (String expected : entry.getValue()) {
                if (actual.equalsIgnoreCase(expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesResourceFilters(TraceAggregate trace, TraceResourceFilterParser.FilterSet resourceFilters) {
        if (trace == null) {
            return resourceFilters == null || resourceFilters.isEmpty();
        }
        Map<String, String> values = new LinkedHashMap<>(trace.getResourceAttributes());
        if (StringUtils.hasText(trace.getServiceName())) {
            values.put("service.name", trace.getServiceName());
        }
        return resourceFilterParser.matches(values, resourceFilters);
    }

    private boolean matchesSpanAttributeFilters(TraceAggregate trace,
                                                TraceResourceFilterParser.FilterSet attributeFilters) {
        if (attributeFilters == null || attributeFilters.isEmpty()) {
            return true;
        }
        if (trace == null || CollectionUtils.isEmpty(trace.spans)) {
            return false;
        }
        return trace.spans.stream()
                .anyMatch(span -> resourceFilterParser.matches(spanAttributeFilterValues(span), attributeFilters));
    }

    private Map<String, String> spanAttributeFilterValues(TraceSpanNodeDto span) {
        if (span == null) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(span.getSpanAttributes())) {
            values.putAll(span.getSpanAttributes());
        }
        String spanName = trimText(span.getSpanName());
        if (spanName != null) {
            values.putIfAbsent("span.name", spanName);
            values.putIfAbsent("span_name", spanName);
            values.putIfAbsent("spanName", spanName);
        }
        return values;
    }

    private TraceResourceFilterParser.FilterSet parseResourceFilters(String resourceFilter) {
        return resourceFilterParser.parse(resourceFilter);
    }

    private Map<String, Set<String>> mergeResourceFilters(Map<String, Set<String>> identityValues,
                                                          Map<String, Set<String>> resourceFilters) {
        if (CollectionUtils.isEmpty(identityValues) && CollectionUtils.isEmpty(resourceFilters)) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        identityValues.forEach((key, values) -> merged.put(key, new LinkedHashSet<>(values)));
        resourceFilters.forEach((key, values) -> merged.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(values));
        return merged;
    }

    private TraceResourceFilterParser.FilterSet removeEntityScopeResourceFilters(
            Map<String, Set<String>> identityValues,
            TraceResourceFilterParser.FilterSet resourceFilters) {
        if (resourceFilters == null || resourceFilters.isEmpty()) {
            return TraceResourceFilterParser.FilterSet.empty();
        }
        return new TraceResourceFilterParser.FilterSet(
                removeEntityScopeResourceFilterMap(identityValues, resourceFilters.include()),
                removeEntityScopeResourceFilterMap(identityValues, resourceFilters.exclude())
        );
    }

    private Map<String, Set<String>> removeEntityScopeResourceFilterMap(Map<String, Set<String>> identityValues,
                                                                        Map<String, Set<String>> resourceFilters) {
        if (CollectionUtils.isEmpty(identityValues) || CollectionUtils.isEmpty(resourceFilters)) {
            return resourceFilters;
        }
        Map<String, Set<String>> filtered = new LinkedHashMap<>();
        resourceFilters.forEach((key, values) -> {
            if (ENTITY_SCOPE_RESOURCE_KEYS.contains(key) && identityValues.containsKey(key)) {
                return;
            }
            filtered.put(key, values);
        });
        return filtered;
    }

    private String resolveCanonicalValue(Map<String, String> resourceAttributes, String key, String serviceName) {
        if ("service.name".equals(key)) {
            return defaultText(serviceName, resourceAttributes.get(key));
        }
        return resourceAttributes.get(key);
    }

    private Map<String, Set<String>> canonicalIdentityValues(ObservedEntityContext entityContext) {
        if (entityContext == null) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> values = new LinkedHashMap<>();
        if (entityContext.getEntity() != null && entityContext.getEntity().getId() != null
                && entityContext.getEntity().getId() > 0) {
            values.computeIfAbsent(OtlpResourceSemanticAttributes.HERTZBEAT_ENTITY_ID, ignored -> new LinkedHashSet<>())
                    .add(String.valueOf(entityContext.getEntity().getId()));
        }
        if (!CollectionUtils.isEmpty(entityContext.getIdentities())) {
            for (EntityIdentity identity : entityContext.getIdentities()) {
                String key = trimText(identity.getIdentityKey());
                String value = trimText(identity.getIdentityValue());
                if (!StringUtils.hasText(key) || !StringUtils.hasText(value)
                        || !EntityCanonicalIdentityRegistry.isCanonicalOtelResourceKey(key)) {
                    continue;
                }
                values.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(value);
            }
        }
        return values.isEmpty() ? Collections.emptyMap() : values;
    }

    private Map<String, Set<String>> traceQueryIdentityValues(ObservedEntityContext entityContext) {
        Map<String, Set<String>> values = canonicalIdentityValues(entityContext);
        if (values.size() <= 1
                || !values.containsKey(OtlpResourceSemanticAttributes.HERTZBEAT_ENTITY_ID)
                || hasExplicitEntityIdIdentity(entityContext)) {
            return values;
        }
        Map<String, Set<String>> relaxedValues = new LinkedHashMap<>(values);
        relaxedValues.remove(OtlpResourceSemanticAttributes.HERTZBEAT_ENTITY_ID);
        return relaxedValues.isEmpty() ? values : relaxedValues;
    }

    private boolean hasExplicitEntityIdIdentity(ObservedEntityContext entityContext) {
        if (entityContext == null || CollectionUtils.isEmpty(entityContext.getIdentities())) {
            return false;
        }
        return entityContext.getIdentities().stream()
                .anyMatch(identity -> OtlpResourceSemanticAttributes.HERTZBEAT_ENTITY_ID.equals(
                        trimText(identity.getIdentityKey())));
    }

    private List<String> preferredSearchTerms(Map<String, Set<String>> identityValues) {
        List<String> preferredKeys = List.of("service.name", "service.instance.id", "host.name", "k8s.deployment.name", "cloud.resource_id");
        List<String> terms = new ArrayList<>();
        for (String key : preferredKeys) {
            Set<String> values = identityValues.get(key);
            if (!CollectionUtils.isEmpty(values)) {
                values.stream().filter(StringUtils::hasText).findFirst().ifPresent(terms::add);
            }
        }
        return terms;
    }

    private void putPreferredFilter(Map<String, String> filters, Map<String, Set<String>> identityValues, String key) {
        Set<String> values = identityValues.get(key);
        if (!CollectionUtils.isEmpty(values)) {
            values.stream().filter(StringUtils::hasText).findFirst().ifPresent(value -> filters.put(key, value));
        }
    }

    private String preferredIdentityValue(Map<String, Set<String>> identityValues, String key) {
        Set<String> values = identityValues.get(key);
        if (CollectionUtils.isEmpty(values)) {
            return null;
        }
        return values.stream().filter(StringUtils::hasText).findFirst().orElse(null);
    }

    private TraceListItemDto toTraceListItem(TraceAggregate aggregate) {
        return new TraceListItemDto(
                aggregate.getTraceId(),
                aggregate.getRootSpanId(),
                aggregate.getServiceName(),
                aggregate.getServiceNamespace(),
                aggregate.getRootSpanName(),
                aggregate.getDurationNanos(),
                aggregate.getStatus(),
                aggregate.getStartTime(),
                aggregate.getErrorSpanCount(),
                aggregate.getResourceAttributes()
        );
    }

    private TraceListItemDto toTraceListItem(Map<String, Object> row) {
        return rowMapper.toTraceListItem(row);
    }

    private TraceDetailDto toTraceDetail(TraceAggregate aggregate) {
        return new TraceDetailDto(
                aggregate.getTraceId(),
                aggregate.getRootSpanId(),
                aggregate.getServiceName(),
                aggregate.getServiceNamespace(),
                aggregate.getRootSpanName(),
                aggregate.getDurationNanos(),
                aggregate.getStatus(),
                aggregate.getStartTime(),
                aggregate.getErrorSpanCount(),
                aggregate.getResourceAttributes(),
                aggregate.getOrderedSpans()
        );
    }

    private TraceSpanNodeDto toSpanNode(Map<String, Object> row) {
        return rowMapper.toSpanNode(row);
    }

    private Double readDoubleValue(Map<String, Object> row, String... keys) {
        return rowMapper.readDoubleValue(row, keys);
    }

    private String readTextValue(Map<String, Object> row, String key) {
        return rowMapper.readTextValue(row, key);
    }

    private Long readLongValue(Map<String, Object> row, String... keys) {
        return rowMapper.readLongValue(row, keys);
    }

    private Integer readIntValue(Map<String, Object> row, String... keys) {
        return rowMapper.readIntValue(row, keys);
    }

    private String resolveEntityTitle(ObservedEntityContext entityContext) {
        if (entityContext == null || entityContext.getEntity() == null) {
            return "entity";
        }
        return defaultText(trimText(entityContext.getEntity().getDisplayName()),
                defaultText(trimText(entityContext.getEntity().getName()), "entity"));
    }

    private String readText(Map<String, Object> row, String key) {
        return rowMapper.readText(row, key);
    }

    private record TraceQueryScope(String serviceName, String serviceNamespace, String environment) {
    }

    private Long readLong(Map<String, Object> row, String key) {
        return rowMapper.readLong(row, key);
    }

    private Long readNonNegativeLong(Map<String, Object> row, String key) {
        return rowMapper.readNonNegativeLong(row, key);
    }

    private Long readTimestamp(Map<String, Object> row, String key) {
        return rowMapper.readTimestamp(row, key);
    }

    private String normalizeStatus(String rawStatus) {
        return rowMapper.normalizeStatus(rawStatus);
    }

    private boolean isErrorStatus(String status) {
        return rowMapper.isErrorStatus(status);
    }

    private String trimText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String defaultText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : trimText(fallback);
    }

    private static final class TraceAggregate {
        private final String traceId;
        private String rootSpanId;
        private String serviceName;
        private String serviceNamespace;
        private String rootSpanName;
        private Long durationNanos;
        private String status;
        private Long startTime;
        private int errorSpanCount;
        private Map<String, String> resourceAttributes = Collections.emptyMap();
        private final List<TraceSpanNodeDto> spans = new ArrayList<>();

        private TraceAggregate(String traceId) {
            this.traceId = traceId;
        }

        private void accept(TraceSpanNodeDto span) {
            this.spans.add(span);
            if (span.isHighlighted()) {
                this.errorSpanCount++;
                this.status = "error";
            } else if (!StringUtils.hasText(this.status)) {
                this.status = span.getStatus();
            }
            if (!StringUtils.hasText(this.serviceName)) {
                this.serviceName = span.getServiceName();
            }
            if (this.serviceNamespace == null) {
                this.serviceNamespace = span.getResourceAttributes().get("service.namespace");
            }
            if (this.startTime == null || (span.getStartTime() != null && span.getStartTime() < this.startTime)) {
                this.startTime = span.getStartTime();
            }
            TraceSpanNodeDto currentRoot = isRoot(span) ? span : null;
            if (currentRoot != null) {
                this.rootSpanId = currentRoot.getSpanId();
                this.rootSpanName = currentRoot.getSpanName();
                this.durationNanos = currentRoot.getDurationNanos();
                this.resourceAttributes = currentRoot.getResourceAttributes();
            }
        }

        private TraceAggregate normalize() {
            this.spans.sort(Comparator.comparing(TraceSpanNodeDto::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())));
            if (!StringUtils.hasText(this.rootSpanId) && !this.spans.isEmpty()) {
                TraceSpanNodeDto first = this.spans.getFirst();
                this.rootSpanId = first.getSpanId();
                this.rootSpanName = first.getSpanName();
                this.durationNanos = first.getDurationNanos();
                this.resourceAttributes = first.getResourceAttributes();
            }
            TraceSpanNodeDto rootSpan = findRootSpan();
            if (rootSpan != null) {
                this.serviceName = preferText(rootSpan.getServiceName(),
                        rootSpan.getResourceAttributes().get("service.name"),
                        this.serviceName);
                this.serviceNamespace = preferText(rootSpan.getResourceAttributes().get("service.namespace"),
                        this.serviceNamespace);
                if (!CollectionUtils.isEmpty(rootSpan.getResourceAttributes())) {
                    this.resourceAttributes = rootSpan.getResourceAttributes();
                }
            }
            if (!StringUtils.hasText(this.serviceName) && !this.spans.isEmpty()) {
                this.serviceName = this.spans.getFirst().getServiceName();
            }
            if (!StringUtils.hasText(this.serviceNamespace) && !CollectionUtils.isEmpty(this.resourceAttributes)) {
                this.serviceNamespace = this.resourceAttributes.get("service.namespace");
            }
            if (!StringUtils.hasText(this.status)) {
                this.status = "unknown";
            }
            if (this.durationNanos == null) {
                this.durationNanos = this.spans.stream()
                        .map(TraceSpanNodeDto::getDurationNanos)
                        .filter(Objects::nonNull)
                        .max(Long::compareTo)
                        .orElse(null);
            }
            return this;
        }

        private TraceSpanNodeDto findRootSpan() {
            if (!StringUtils.hasText(this.rootSpanId)) {
                return null;
            }
            return this.spans.stream()
                    .filter(span -> this.rootSpanId.equals(span.getSpanId()))
                    .findFirst()
                    .orElse(null);
        }

        private String preferText(String... values) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
            return null;
        }

        private boolean isRoot(TraceSpanNodeDto span) {
            return !StringUtils.hasText(span.getParentSpanId());
        }

        private List<TraceSpanNodeDto> getOrderedSpans() {
            Map<String, List<TraceSpanNodeDto>> children = new LinkedHashMap<>();
            List<TraceSpanNodeDto> roots = new ArrayList<>();
            for (TraceSpanNodeDto span : this.spans) {
                if (!StringUtils.hasText(span.getParentSpanId())) {
                    roots.add(span);
                    continue;
                }
                children.computeIfAbsent(span.getParentSpanId(), ignored -> new ArrayList<>()).add(span);
            }
            roots.sort(Comparator.comparing(TraceSpanNodeDto::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())));
            children.values().forEach(list -> list.sort(Comparator.comparing(TraceSpanNodeDto::getStartTime,
                    Comparator.nullsLast(Comparator.naturalOrder()))));
            List<TraceSpanNodeDto> ordered = new ArrayList<>();
            for (TraceSpanNodeDto root : roots) {
                appendNode(root, children, ordered);
            }
            for (TraceSpanNodeDto span : this.spans) {
                if (!ordered.contains(span)) {
                    ordered.add(span);
                }
            }
            return ordered;
        }

        private void appendNode(TraceSpanNodeDto node, Map<String, List<TraceSpanNodeDto>> children,
                                List<TraceSpanNodeDto> ordered) {
            ordered.add(node);
            for (TraceSpanNodeDto child : children.getOrDefault(node.getSpanId(), List.of())) {
                appendNode(child, children, ordered);
            }
        }

        private String getTraceId() {
            return traceId;
        }

        private String getRootSpanId() {
            return rootSpanId;
        }

        private String getServiceName() {
            return serviceName;
        }

        private String getServiceNamespace() {
            return serviceNamespace;
        }

        private String getRootSpanName() {
            return rootSpanName;
        }

        private Long getDurationNanos() {
            return durationNanos;
        }

        private String getStatus() {
            return status;
        }

        private Long getStartTime() {
            return startTime;
        }

        private int getErrorSpanCount() {
            return errorSpanCount;
        }

        private Map<String, String> getResourceAttributes() {
            return resourceAttributes;
        }
    }
}
