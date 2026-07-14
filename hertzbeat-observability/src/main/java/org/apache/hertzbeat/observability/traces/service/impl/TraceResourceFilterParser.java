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

package org.apache.hertzbeat.observability.traces.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Parses and evaluates the trace workspace resource-filter language.
 */
@Component
final class TraceResourceFilterParser {

    private static final String CONTAINS_PREFIX = "__hz_contains__:";
    private static final String NOT_CONTAINS_PREFIX = "__hz_not_contains__:";
    private static final String EXISTS_VALUE = "__hz_exists__";
    private static final String NOT_EXISTS_VALUE = "__hz_not_exists__";
    private static final Pattern LIST_OPERATOR_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z0-9._:-]+)\\s+(NOT\\s+IN|IN)\\s*(\\(.+\\))\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_EQUALS_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z0-9._:-]+)\\s*!=\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TEXT_OPERATOR_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z0-9._:-]+)\\s+(NOT\\s+CONTAINS|CONTAINS)\\s+(.+)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRESENCE_OPERATOR_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z0-9._:-]+)\\s+(NOT\\s+EXISTS|EXISTS)\\s*$",
            Pattern.CASE_INSENSITIVE);

    FilterSet parse(String resourceFilter) {
        if (!StringUtils.hasText(resourceFilter)) {
            return FilterSet.empty();
        }
        Map<String, Set<String>> includeFilters = new LinkedHashMap<>();
        Map<String, Set<String>> excludeFilters = new LinkedHashMap<>();
        for (String clause : splitClauses(resourceFilter)) {
            String trimmedClause = trimToNull(clause);
            if (!StringUtils.hasText(trimmedClause)) {
                continue;
            }
            if (appendListValues(includeFilters, excludeFilters, trimmedClause)
                    || appendTextValue(includeFilters, trimmedClause)
                    || appendPresenceValue(includeFilters, trimmedClause)
                    || appendNotEqualsValue(excludeFilters, trimmedClause)) {
                continue;
            }
            appendExactValue(includeFilters, trimmedClause);
        }
        return new FilterSet(includeFilters, excludeFilters);
    }

    boolean matches(Map<String, String> values, FilterSet filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Map<String, String> source = values == null ? Collections.emptyMap() : values;
        return matchesIncluded(source, filters.include()) && matchesExcluded(source, filters.exclude());
    }

    private void appendExactValue(Map<String, Set<String>> includeFilters, String clause) {
        int separatorIndex = separatorIndex(clause);
        if (separatorIndex <= 0 || separatorIndex >= clause.length() - 1) {
            return;
        }
        String key = trimToNull(clause.substring(0, separatorIndex));
        String value = stripQuotes(trimToNull(clause.substring(separatorIndex + 1)));
        if (isSafeKey(key) && StringUtils.hasText(value)) {
            includeFilters.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(value);
        }
    }

    private boolean appendTextValue(Map<String, Set<String>> includeFilters, String clause) {
        Matcher matcher = TEXT_OPERATOR_PATTERN.matcher(clause);
        if (!matcher.matches()) {
            return false;
        }
        String key = trimToNull(matcher.group(1));
        String operator = trimToNull(matcher.group(2));
        String value = stripQuotes(trimToNull(matcher.group(3)));
        if (!isSafeKey(key) || !StringUtils.hasText(operator) || !StringUtils.hasText(value)) {
            return false;
        }
        String prefix = normalizeOperator(operator).equals("not contains") ? NOT_CONTAINS_PREFIX : CONTAINS_PREFIX;
        includeFilters.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(prefix + value);
        return true;
    }

    private boolean appendPresenceValue(Map<String, Set<String>> includeFilters, String clause) {
        Matcher matcher = PRESENCE_OPERATOR_PATTERN.matcher(clause);
        if (!matcher.matches()) {
            return false;
        }
        String key = trimToNull(matcher.group(1));
        String operator = trimToNull(matcher.group(2));
        if (!isSafeKey(key) || !StringUtils.hasText(operator)) {
            return false;
        }
        String value = normalizeOperator(operator).equals("not exists") ? NOT_EXISTS_VALUE : EXISTS_VALUE;
        includeFilters.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(value);
        return true;
    }

    private boolean appendListValues(Map<String, Set<String>> includeFilters,
                                     Map<String, Set<String>> excludeFilters,
                                     String clause) {
        Matcher matcher = LIST_OPERATOR_PATTERN.matcher(clause);
        if (!matcher.matches()) {
            return false;
        }
        String key = trimToNull(matcher.group(1));
        String operator = trimToNull(matcher.group(2));
        String valueList = trimToNull(matcher.group(3));
        if (!isSafeKey(key) || !StringUtils.hasText(operator) || !StringUtils.hasText(valueList)
                || valueList.length() < 2 || !valueList.startsWith("(") || !valueList.endsWith(")")) {
            return false;
        }
        Map<String, Set<String>> target = normalizeOperator(operator).equals("not in")
                ? excludeFilters
                : includeFilters;
        for (String value : splitListValues(valueList.substring(1, valueList.length() - 1))) {
            String normalizedValue = stripQuotes(trimToNull(value));
            if (StringUtils.hasText(normalizedValue)) {
                target.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(normalizedValue);
            }
        }
        return target.containsKey(key);
    }

    private boolean appendNotEqualsValue(Map<String, Set<String>> excludeFilters, String clause) {
        Matcher matcher = NOT_EQUALS_PATTERN.matcher(clause);
        if (!matcher.matches()) {
            return false;
        }
        String key = trimToNull(matcher.group(1));
        String value = stripQuotes(trimToNull(matcher.group(2)));
        if (!isSafeKey(key) || !StringUtils.hasText(value)) {
            return false;
        }
        excludeFilters.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(value);
        return true;
    }

    private boolean matchesIncluded(Map<String, String> source, Map<String, Set<String>> filters) {
        for (Map.Entry<String, Set<String>> entry : filters.entrySet()) {
            String actual = trimToNull(source.get(entry.getKey()));
            boolean keyExists = source.containsKey(entry.getKey());
            boolean matched = entry.getValue().stream()
                    .filter(StringUtils::hasText)
                    .anyMatch(expected -> matchesValue(actual, expected, keyExists));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesExcluded(Map<String, String> source, Map<String, Set<String>> filters) {
        for (Map.Entry<String, Set<String>> entry : filters.entrySet()) {
            String actual = trimToNull(source.get(entry.getKey()));
            if (!StringUtils.hasText(actual)) {
                continue;
            }
            boolean excluded = entry.getValue().stream()
                    .filter(StringUtils::hasText)
                    .anyMatch(expected -> matchesExact(actual, expected));
            if (excluded) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesValue(String actualValue, String expectedValue, boolean keyExists) {
        if (EXISTS_VALUE.equals(expectedValue)) {
            return keyExists;
        }
        if (NOT_EXISTS_VALUE.equals(expectedValue)) {
            return !keyExists;
        }
        if (isContainsValue(expectedValue)) {
            return matchesContained(actualValue, expectedValue.substring(CONTAINS_PREFIX.length()));
        }
        if (isNotContainsValue(expectedValue)) {
            return !matchesContained(actualValue, expectedValue.substring(NOT_CONTAINS_PREFIX.length()));
        }
        return matchesExact(actualValue, expectedValue);
    }

    private boolean matchesExact(String actualValue, String expectedValue) {
        return StringUtils.hasText(actualValue) && StringUtils.hasText(expectedValue)
                && actualValue.equalsIgnoreCase(expectedValue);
    }

    private boolean matchesContained(String actualValue, String expectedValue) {
        return StringUtils.hasText(actualValue) && StringUtils.hasText(expectedValue)
                && actualValue.toLowerCase(Locale.ROOT).contains(expectedValue.toLowerCase(Locale.ROOT));
    }

    private List<String> splitClauses(String filter) {
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
            } else if (character == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && character == ',') {
                addClause(clauses, current);
            } else if (depth == 0 && isAndDelimiter(filter, index)) {
                addClause(clauses, current);
                index += 4;
            } else {
                current.append(character);
            }
        }
        addClause(clauses, current);
        return clauses;
    }

    private List<String> splitListValues(String values) {
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
            } else if (character == '\'' || character == '"') {
                quote = character;
                current.append(character);
            } else if (character == ',') {
                addClause(result, current);
            } else {
                current.append(character);
            }
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

    private int separatorIndex(String clause) {
        int equalsIndex = clause.indexOf('=');
        int colonIndex = clause.indexOf(':');
        if (equalsIndex < 0) {
            return colonIndex;
        }
        if (colonIndex < 0) {
            return equalsIndex;
        }
        return Math.min(equalsIndex, colonIndex);
    }

    private String stripQuotes(String value) {
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

    boolean isSafeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && character != '.' && character != '_' && character != '-' && character != ':') {
                return false;
            }
        }
        return true;
    }

    private String normalizeOperator(String operator) {
        return operator.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static boolean isComplexValue(String value) {
        return isContainsValue(value) || isNotContainsValue(value)
                || EXISTS_VALUE.equals(value) || NOT_EXISTS_VALUE.equals(value);
    }

    private static boolean isContainsValue(String value) {
        return value != null && value.startsWith(CONTAINS_PREFIX);
    }

    private static boolean isNotContainsValue(String value) {
        return value != null && value.startsWith(NOT_CONTAINS_PREFIX);
    }

    record FilterSet(Map<String, Set<String>> include, Map<String, Set<String>> exclude) {

        static FilterSet empty() {
            return new FilterSet(Collections.emptyMap(), Collections.emptyMap());
        }

        FilterSet {
            include = include == null ? Collections.emptyMap() : include;
            exclude = exclude == null ? Collections.emptyMap() : exclude;
        }

        boolean isEmpty() {
            return include.isEmpty() && exclude.isEmpty();
        }

        boolean requiresRowFallback() {
            return !exclude.isEmpty() || include.values().stream()
                    .flatMap(Set::stream)
                    .anyMatch(TraceResourceFilterParser::isComplexValue);
        }

        Map<String, Set<String>> pushableInclude() {
            if (include.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, Set<String>> pushable = new LinkedHashMap<>();
            include.forEach((key, values) -> {
                Set<String> exactValues = new LinkedHashSet<>();
                values.stream().filter(value -> !isComplexValue(value)).forEach(exactValues::add);
                if (!exactValues.isEmpty()) {
                    pushable.put(key, exactValues);
                }
            });
            return pushable;
        }
    }
}
