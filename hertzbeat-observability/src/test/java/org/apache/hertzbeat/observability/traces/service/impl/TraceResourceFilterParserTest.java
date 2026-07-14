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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TraceResourceFilterParserTest {

    private final TraceResourceFilterParser parser = new TraceResourceFilterParser();

    @Test
    void parsesFriendlyOperatorsAndEvaluatesTheResult() {
        TraceResourceFilterParser.FilterSet filters = parser.parse(
                "service.name=checkout and k8s.namespace.name in ('shop', 'payments') "
                        + "and host.name exists and http.route not contains admin "
                        + "and deployment.environment.name != dev");

        assertEquals(Map.of(
                "deployment.environment.name", Set.of("dev")
        ), filters.exclude());
        assertTrue(filters.requiresRowFallback());
        assertEquals(Map.of(
                "service.name", Set.of("checkout"),
                "k8s.namespace.name", Set.of("shop", "payments")
        ), filters.pushableInclude());

        assertTrue(parser.matches(Map.of(
                "service.name", "checkout",
                "k8s.namespace.name", "shop",
                "host.name", "node-1",
                "http.route", "/checkout",
                "deployment.environment.name", "prod"
        ), filters));
        assertFalse(parser.matches(Map.of(
                "service.name", "checkout",
                "k8s.namespace.name", "shop",
                "host.name", "node-1",
                "http.route", "/admin/users",
                "deployment.environment.name", "prod"
        ), filters));
    }

    @Test
    void ignoresUnsafeAndIncompleteClauses() {
        TraceResourceFilterParser.FilterSet filters = parser.parse(
                "service.name=checkout, bad key=value, host.name=, =missing");

        assertEquals(Map.of("service.name", Set.of("checkout")), filters.include());
        assertTrue(filters.exclude().isEmpty());
    }
}
