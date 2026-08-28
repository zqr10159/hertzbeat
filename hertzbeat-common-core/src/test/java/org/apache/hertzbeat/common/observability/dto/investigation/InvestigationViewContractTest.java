/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hertzbeat.common.observability.dto.investigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InvestigationViewContractTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void serializesFrozenTraceContractAndBoundedEnumCodes() throws Exception {
        TraceInvestigationView view = new TraceInvestigationView(
                "0123456789abcdef0123456789abcdef",
                null,
                new InvestigationWindow(100L, 200L),
                TraceInvestigationView.GanttBlock.empty(),
                TraceInvestigationView.LogsBlock.empty(),
                TraceInvestigationView.RedBlock.unavailable(
                        InvestigationReason.IDENTITY_UNAVAILABLE, null),
                TraceInvestigationView.MetricsBlock.unavailable(
                        InvestigationReason.QUERY_STRATEGY_UNAVAILABLE),
                TraceInvestigationView.DependenciesBlock.empty());

        String json = jsonMapper.writeValueAsString(view);

        assertEquals("empty", jsonMapper.readTree(json).path("gantt").path("state").asText());
        assertEquals("no_data", jsonMapper.readTree(json).path("gantt").path("reason").asText());
        assertEquals("greptime_traces", jsonMapper.readTree(json).path("gantt").path("source").asText());
        assertEquals("query_strategy_unavailable",
                jsonMapper.readTree(json).path("metrics").path("reason").asText());
        assertEquals(100L, jsonMapper.readTree(json).path("window").path("start").asLong());
        assertEquals(200L, jsonMapper.readTree(json).path("window").path("end").asLong());
    }

    @Test
    void logWireKeepsNanosecondTimestampsAsDecimalStrings() throws Exception {
        InvestigationServiceIdentity identity = new InvestigationServiceIdentity(
                "team-a", "7", "service", "checkout", "payments", "prod");
        InvestigationLogRecord selected = new InvestigationLogRecord(
                "event-7",
                "1787934874782123456",
                "1787934874782123999",
                17,
                "ERROR",
                "checkout failed",
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                identity,
                Map.of("http.route", "/checkout"),
                Map.of("service.name", "checkout"));
        LogInvestigationView view = new LogInvestigationView(
                "event-7",
                new InvestigationWindow(1787934874000L, 1787934875000L),
                LogInvestigationView.SelectedLogBlock.ready(selected),
                LogInvestigationView.TraceBlock.empty(InvestigationReason.NOT_CORRELATED),
                LogInvestigationView.MetricsBlock.unavailable(
                        InvestigationReason.QUERY_STRATEGY_UNAVAILABLE),
                LogInvestigationView.NearbyLogsBlock.empty());

        String json = jsonMapper.writeValueAsString(view);

        assertEquals("1787934874782123456",
                jsonMapper.readTree(json).path("selectedLog").path("log").path("timeUnixNano").asText());
        assertEquals("1787934874782123999",
                jsonMapper.readTree(json).path("selectedLog").path("log").path("observedTimeUnixNano").asText());
    }

    @Test
    void rejectsUppercasePersistedLogCorrelationIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new InvestigationLogRecord(
                "event-7", "1787934874782123456", null, 17, "ERROR", "failed",
                "0123456789ABCDEF0123456789ABCDEF", "0123456789ABCDEF", null, Map.of(), Map.of()));
    }

    @Test
    void rejectsReadyEvidenceWithoutObservedValuesAndInvalidWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new InvestigationWindow(100L, 100L));
        assertThrows(IllegalArgumentException.class,
                () -> new InvestigationServiceIdentity(
                        "team-a\nleak", "7", "service", "checkout", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InvestigationServiceIdentity(
                        "team-a", "not-a-positive-id", "service", "checkout", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InvestigationServiceIdentity(
                        "team-a", "7", "service", " ", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceInvestigationView.LogsBlock(
                        InvestigationEvidenceState.READY,
                        InvestigationReason.OBSERVED,
                        InvestigationSource.GREPTIME_LOGS,
                        false,
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LogInvestigationView.SelectedLogBlock(
                        InvestigationEvidenceState.UNAVAILABLE,
                        InvestigationReason.STORAGE_UNAVAILABLE,
                        InvestigationSource.GREPTIME_LOGS,
                        new InvestigationLogRecord(
                                "event-7", "1", null, null, null, null, null, null,
                                null, Map.of(), Map.of())));
    }
}
