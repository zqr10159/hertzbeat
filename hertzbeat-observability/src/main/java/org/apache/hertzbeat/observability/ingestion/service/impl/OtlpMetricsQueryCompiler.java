/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.observability.ingestion.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hertzbeat.common.observability.dto.metrics.OtlpMetricsConsoleDto;
import org.apache.hertzbeat.common.observability.model.EntityCanonicalIdentityRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Compiles the metrics workspace query model into PromQL.
 */
@Component
final class OtlpMetricsQueryCompiler {

    private static final List<String> ENTITY_CONTEXT_GROUP_LABELS = List.of(
            "__name__",
            "service_name",
            "service_namespace",
            "deployment_environment_name",
            "hertzbeat_entity_id",
            "hertzbeat_entity_type",
            "hertzbeat_entity_name"
    );
    private static final String DEFAULT_GROUP_BY = String.join(", ", ENTITY_CONTEXT_GROUP_LABELS);
    private static final String DEFAULT_AGGREGATION = "sum";
    private static final Pattern FILTER_MATCHER = Pattern.compile(
            "\\s*([A-Za-z_:][A-Za-z0-9_.:-]*)\\s*(=~|!~|!=|=)\\s*"
                    + "(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([^,\\s]+))\\s*"
    );
    private static final Pattern FILTER_LIST_OPERATOR_PATTERN = Pattern.compile(
            "\\s*([A-Za-z_:][A-Za-z0-9_.:-]*)\\s+(NOT\\s+IN|IN)\\s*(\\(.+\\))\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILTER_TEXT_OPERATOR_PATTERN = Pattern.compile(
            "\\s*([A-Za-z_:][A-Za-z0-9_.:-]*)\\s+(NOT\\s+CONTAINS|CONTAINS)\\s+(.+)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILTER_PRESENCE_OPERATOR_PATTERN = Pattern.compile(
            "\\s*([A-Za-z_:][A-Za-z0-9_.:-]*)\\s+(NOT\\s+EXISTS|EXISTS)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_METRIC_NAME = Pattern.compile("[A-Za-z_:][A-Za-z0-9_:.-]*");

    List<String> compileMetricQueries(OtlpMetricsConsoleDto.Context context,
                                      String metricName,
                                      String filter,
                                      String groupBy,
                                      String aggregation,
                                      String temporalAggregation,
                                      String operationName) {
        String normalizedOperationName = trimToNull(operationName);
        if (!StringUtils.hasText(normalizedOperationName)) {
            String query = compileMetricQuery(context, metricName, filter, groupBy, aggregation,
                    temporalAggregation, null, null);
            return StringUtils.hasText(query) ? List.of(query) : List.of();
        }
        List<String> queries = new ArrayList<>();
        for (String operationLabel : List.of("operation_name", "http_route")) {
            String query = compileMetricQuery(context, metricName, filter, groupBy, aggregation,
                    temporalAggregation, operationLabel, normalizedOperationName);
            if (StringUtils.hasText(query)) {
                queries.add(query);
            }
        }
        return List.copyOf(queries);
    }

    List<String> compileExplicitQueries(OtlpMetricsConsoleDto.Context context,
                                        String query,
                                        String filter,
                                        String groupBy,
                                        String aggregation,
                                        String temporalAggregation,
                                        String operationName) {
        String normalizedQuery = trimToNull(query);
        if (!StringUtils.hasText(normalizedQuery) || !SIMPLE_METRIC_NAME.matcher(normalizedQuery).matches()) {
            return StringUtils.hasText(normalizedQuery) ? List.of(normalizedQuery) : List.of();
        }
        List<String> generatedQueries = compileMetricQueries(context, normalizedQuery, filter, groupBy,
                aggregation, temporalAggregation, operationName);
        return generatedQueries.isEmpty() ? List.of(normalizedQuery) : generatedQueries;
    }

    List<String> parseFilterMatchers(String filter, Set<String> excludedLabels) {
        String normalized = trimToNull(filter);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        Set<String> exclusions = excludedLabels == null ? Set.of() : excludedLabels;
        List<String> matchers = new ArrayList<>();
        for (String rawClause : splitFilterClauses(normalized)) {
            String clause = trimToNull(rawClause);
            if (!StringUtils.hasText(clause)) {
                continue;
            }
            String parsedMatcher = parseFriendlyFilterMatcher(clause, exclusions);
            if (StringUtils.hasText(parsedMatcher)) {
                matchers.add(parsedMatcher);
                continue;
            }
            LabelMatcher matcher = parseMatcher(clause, exclusions);
            if (matcher != null) {
                matchers.add(matcher.toPromql());
            }
        }
        return List.copyOf(matchers);
    }

    List<LabelMatcher> parseResourceMatchers(String filter) {
        String normalized = trimToNull(filter);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<LabelMatcher> matchers = new ArrayList<>();
        for (String rawClause : splitFilterClauses(normalized)) {
            String clause = trimToNull(rawClause);
            if (!StringUtils.hasText(clause)) {
                continue;
            }
            String friendlyMatcher = parseFriendlyFilterMatcher(clause, Set.of());
            LabelMatcher matcher = parseMatcher(
                    StringUtils.hasText(friendlyMatcher) ? friendlyMatcher : clause,
                    Set.of());
            if (matcher != null) {
                matchers.add(matcher);
            }
        }
        return List.copyOf(matchers);
    }

    String normalizeMetricName(String metricName) {
        String normalized = trimToNull(metricName);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        normalized = normalized.replaceAll("[^A-Za-z0-9_:]", "_");
        normalized = normalized.replaceAll("_+", "_");
        if (!normalized.isEmpty() && Character.isDigit(normalized.charAt(0))) {
            normalized = "_" + normalized;
        }
        return normalized;
    }

    String escapeLabelValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String compileMetricQuery(OtlpMetricsConsoleDto.Context context,
                                      String metricName,
                                      String filter,
                                      String groupBy,
                                      String aggregation,
                                      String temporalAggregation,
                                      String operationLabel,
                                      String operationName) {
        if (context == null || !StringUtils.hasText(context.getServiceName())) {
            return null;
        }
        String normalizedMetricName = normalizeMetricName(metricName);
        if (!StringUtils.hasText(normalizedMetricName)) {
            return null;
        }
        List<String> matchers = new ArrayList<>();
        Set<String> scopedLabels = new LinkedHashSet<>();
        addScopedMatcher(matchers, scopedLabels, "__name__", normalizedMetricName);
        addScopedMatcher(matchers, scopedLabels, "service_name", context.getServiceName());
        addScopedMatcher(matchers, scopedLabels, "service_namespace", context.getServiceNamespace());
        addScopedMatcher(matchers, scopedLabels, "deployment_environment_name", context.getEnvironment());
        if (context.getEntityId() != null) {
            addScopedMatcher(matchers, scopedLabels, "hertzbeat_entity_id", String.valueOf(context.getEntityId()));
        }
        addScopedMatcher(matchers, scopedLabels, "hertzbeat_entity_type", context.getEntityType());
        addScopedMatcher(matchers, scopedLabels, operationLabel, operationName);
        matchers.addAll(parseFilterMatchers(filter, scopedLabels));
        String selector = "{" + String.join(", ", matchers) + "}";
        return normalizeAggregation(aggregation)
                + " by (" + normalizeGroupBy(groupBy) + ") ("
                + wrapTemporalAggregation(selector, temporalAggregation) + ")";
    }

    private void addScopedMatcher(List<String> matchers, Set<String> scopedLabels, String label, String value) {
        if (!StringUtils.hasText(label) || !StringUtils.hasText(value)) {
            return;
        }
        matchers.add(label + "=\"" + escapeLabelValue(value) + "\"");
        scopedLabels.add(label);
    }

    private LabelMatcher parseMatcher(String clause, Set<String> excludedLabels) {
        Matcher matcher = FILTER_MATCHER.matcher(clause);
        if (!matcher.matches()) {
            return null;
        }
        String labelName = normalizeLabelName(matcher.group(1));
        if (!isLabelName(labelName) || excludedLabels.contains(labelName)) {
            return null;
        }
        String labelValue = firstText(matcher.group(3), matcher.group(4), matcher.group(5));
        if (!StringUtils.hasText(labelValue)) {
            return null;
        }
        return new LabelMatcher(labelName, matcher.group(2), labelValue);
    }

    private String parseFriendlyFilterMatcher(String clause, Set<String> excludedLabels) {
        String listMatcher = parseListFilterMatcher(clause, excludedLabels);
        if (StringUtils.hasText(listMatcher)) {
            return listMatcher;
        }
        String textMatcher = parseTextFilterMatcher(clause, excludedLabels);
        if (StringUtils.hasText(textMatcher)) {
            return textMatcher;
        }
        return parsePresenceFilterMatcher(clause, excludedLabels);
    }

    private String parseListFilterMatcher(String clause, Set<String> excludedLabels) {
        Matcher matcher = FILTER_LIST_OPERATOR_PATTERN.matcher(clause);
        if (!matcher.matches()) {
            return null;
        }
        String labelName = normalizeLabelName(matcher.group(1));
        if (!isLabelName(labelName) || excludedLabels.contains(labelName)) {
            return null;
        }
        String valueList = trimToNull(matcher.group(3));
        if (!StringUtils.hasText(valueList) || valueList.length() < 2
                || !valueList.startsWith("(") || !valueList.endsWith(")")) {
            return null;
        }
        List<String> values = splitFilterListValues(valueList.substring(1, valueList.length() - 1)).stream()
                .map(value -> stripFilterQuotes(trimToNull(value)))
                .filter(StringUtils::hasText)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        String regex = "^(?:" + values.stream()
                .map(this::escapeRegexValue)
                .collect(java.util.stream.Collectors.joining("|")) + ")$";
        String operator = trimToNull(matcher.group(2));
        String promqlOperator = operator != null && operator.replaceAll("\\s+", " ").equalsIgnoreCase("not in")
                ? "!~"
                : "=~";
        return new LabelMatcher(labelName, promqlOperator, regex).toPromql();
    }

    private String parseTextFilterMatcher(String clause, Set<String> excludedLabels) {
        Matcher matcher = FILTER_TEXT_OPERATOR_PATTERN.matcher(clause);
        if (!matcher.matches()) {
            return null;
        }
        String labelName = normalizeLabelName(matcher.group(1));
        if (!isLabelName(labelName) || excludedLabels.contains(labelName)) {
            return null;
        }
        String value = stripFilterQuotes(trimToNull(matcher.group(3)));
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String regex = ".*" + escapeRegexValue(value) + ".*";
        String operator = trimToNull(matcher.group(2));
        String promqlOperator = operator != null
                && operator.replaceAll("\\s+", " ").equalsIgnoreCase("not contains") ? "!~" : "=~";
        return new LabelMatcher(labelName, promqlOperator, regex).toPromql();
    }

    private String parsePresenceFilterMatcher(String clause, Set<String> excludedLabels) {
        Matcher matcher = FILTER_PRESENCE_OPERATOR_PATTERN.matcher(clause);
        if (!matcher.matches()) {
            return null;
        }
        String labelName = normalizeLabelName(matcher.group(1));
        if (!isLabelName(labelName) || excludedLabels.contains(labelName)) {
            return null;
        }
        String operator = trimToNull(matcher.group(2));
        String promqlOperator = operator != null
                && operator.replaceAll("\\s+", " ").equalsIgnoreCase("not exists") ? "!~" : "=~";
        return new LabelMatcher(labelName, promqlOperator, ".+").toPromql();
    }

    private List<String> splitFilterClauses(String filter) {
        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int index = 0; index < filter.length(); index++) {
            char character = filter.charAt(index);
            if (quote != 0) {
                current.append(character);
                if (character == quote) {
                    quote = 0;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                current.append(character);
                continue;
            }
            if (character == '(') {
                depth++;
                current.append(character);
                continue;
            }
            if (character == ')') {
                depth = Math.max(0, depth - 1);
                current.append(character);
                continue;
            }
            if (depth == 0 && character == ',') {
                addClause(clauses, current);
                continue;
            }
            if (depth == 0 && isAndDelimiter(filter, index)) {
                addClause(clauses, current);
                index += 4;
                continue;
            }
            current.append(character);
        }
        addClause(clauses, current);
        return clauses;
    }

    private List<String> splitFilterListValues(String values) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < values.length(); index++) {
            char character = values.charAt(index);
            if (quote != 0) {
                current.append(character);
                if (character == quote) {
                    quote = 0;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                quote = character;
                current.append(character);
                continue;
            }
            if (character == ',') {
                addClause(result, current);
                continue;
            }
            current.append(character);
        }
        addClause(result, current);
        return result;
    }

    private void addClause(List<String> clauses, StringBuilder current) {
        String clause = trimToNull(current.toString());
        if (StringUtils.hasText(clause)) {
            clauses.add(clause);
        }
        current.setLength(0);
    }

    private boolean isAndDelimiter(String value, int index) {
        return index + 5 <= value.length() && value.regionMatches(true, index, " and ", 0, 5);
    }

    private String stripFilterQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return trimToNull(value.substring(1, value.length() - 1));
        }
        return value;
    }

    private String escapeRegexValue(String value) {
        String normalized = trimToNull(value);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if ("\\.^$|?*+()[]{}".indexOf(character) >= 0) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    String normalizeLabelName(String label) {
        String normalized = trimToNull(label);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        normalized = normalized.replaceAll("[^A-Za-z0-9_:]", "_");
        normalized = normalized.replaceAll("_+", "_");
        if (!normalized.isEmpty() && Character.isDigit(normalized.charAt(0))) {
            normalized = "_" + normalized;
        }
        return normalized;
    }

    private String normalizeAggregation(String aggregation) {
        String normalized = trimToNull(aggregation);
        return StringUtils.hasText(normalized) ? normalized : DEFAULT_AGGREGATION;
    }

    private String wrapTemporalAggregation(String selector, String temporalAggregation) {
        String normalized = trimToNull(temporalAggregation);
        if (!StringUtils.hasText(normalized) || "raw".equalsIgnoreCase(normalized)) {
            return selector;
        }
        String function = normalized.toLowerCase(Locale.ROOT);
        if (!List.of("rate", "increase", "delta").contains(function)) {
            return selector;
        }
        return function + "(" + selector + "[5m])";
    }

    private String normalizeGroupBy(String groupBy) {
        String normalized = trimToNull(groupBy);
        if (!StringUtils.hasText(normalized)) {
            return DEFAULT_GROUP_BY;
        }
        LinkedHashSet<String> groupLabels = new LinkedHashSet<>();
        for (String label : normalized.split(",")) {
            String resolvedLabel = normalizeGroupByLabel(trimToNull(label));
            if (StringUtils.hasText(resolvedLabel)) {
                groupLabels.add(resolvedLabel);
            }
        }
        groupLabels.addAll(ENTITY_CONTEXT_GROUP_LABELS);
        return String.join(", ", groupLabels);
    }

    private String normalizeGroupByLabel(String label) {
        if (!StringUtils.hasText(label)) {
            return null;
        }
        String resourceKey = label.toLowerCase(Locale.ROOT);
        if (resourceKey.startsWith("resource:")) {
            resourceKey = resourceKey.substring("resource:".length());
        }
        if (EntityCanonicalIdentityRegistry.isCanonicalOtelResourceKey(resourceKey)
                || resourceKey.startsWith("hertzbeat.")) {
            return resourceKey.replace('.', '_').replace('-', '_');
        }
        return isLabelName(label) ? label : null;
    }

    private boolean isLabelName(String label) {
        return label != null && label.matches("[A-Za-z_:][A-Za-z0-9_:]*");
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (StringUtils.hasText(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    record LabelMatcher(String label, String operator, String value) {

        private String toPromql() {
            return label + operator + "\"" + escape(value) + "\"";
        }

        private static String escape(String value) {
            if (!StringUtils.hasText(value)) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
