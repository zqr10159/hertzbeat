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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.apache.hertzbeat.common.observability.dto.metrics.OtlpMetricsConsoleDto;
import org.junit.jupiter.api.Test;

class OtlpMetricsQueryCompilerTest {

    private final OtlpMetricsQueryCompiler compiler = new OtlpMetricsQueryCompiler();

    @Test
    void compilesScopedFriendlyFiltersAndOperationFallbacks() {
        OtlpMetricsConsoleDto.Context context = new OtlpMetricsConsoleDto.Context(
                42L,
                "service",
                "Checkout",
                "checkout",
                "shop",
                "prod",
                null,
                1000L,
                2000L
        );

        List<String> queries = compiler.compileMetricQueries(
                context,
                "http.server-duration.count",
                "service.name='ignored' and http.method contains POST and status.code in (500, 503)",
                "resource:k8s.namespace.name",
                "avg",
                "rate",
                "POST /checkout"
        );

        String groupBy = "k8s_namespace_name, __name__, service_name, service_namespace, "
                + "deployment_environment_name, hertzbeat_entity_id, hertzbeat_entity_type, hertzbeat_entity_name";
        String commonMatchers = "__name__=\"http_server_duration_count\", service_name=\"checkout\", "
                + "service_namespace=\"shop\", deployment_environment_name=\"prod\", "
                + "hertzbeat_entity_id=\"42\", hertzbeat_entity_type=\"service\"";
        String friendlyMatchers = "http_method=~\".*POST.*\", status_code=~\"^(?:500|503)$\"";
        assertEquals(List.of(
                "avg by (" + groupBy + ") (rate({" + commonMatchers
                        + ", operation_name=\"POST /checkout\", " + friendlyMatchers + "}[5m]))",
                "avg by (" + groupBy + ") (rate({" + commonMatchers
                        + ", http_route=\"POST /checkout\", " + friendlyMatchers + "}[5m]))"
        ), queries);
    }

    @Test
    void preservesAdvancedPromqlQueries() {
        OtlpMetricsConsoleDto.Context context = new OtlpMetricsConsoleDto.Context(
                null, null, null, "checkout", null, null, null, 1000L, 2000L);
        String query = "sum(rate(http_server_request_duration_count[5m]))";

        assertEquals(List.of(query), compiler.compileExplicitQueries(
                context, query, "status.code=500", null, null, null, null));
    }

    @Test
    void parsesFriendlyFiltersIntoStructuredMatchers() {
        assertEquals(List.of(
                new OtlpMetricsQueryCompiler.LabelMatcher(
                        "k8s_namespace_name", "=~", "^(?:shop|payments)$"),
                new OtlpMetricsQueryCompiler.LabelMatcher("host_name", "=~", ".+")
        ), compiler.parseResourceMatchers(
                "k8s.namespace.name in ('shop', 'payments') and host.name exists"));
    }
}
