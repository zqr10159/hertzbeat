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

package org.apache.hertzbeat.observability.logs.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.apache.hertzbeat.warehouse.store.history.tsdb.HistoryDataReader;
import org.junit.jupiter.api.Test;

class HistoryDataReaderFallbackTest {

    @Test
    void returnsFirstSupportedResultThatMatchesTheCallerPolicy() {
        HistoryDataReader unsupported = mock(HistoryDataReader.class);
        HistoryDataReader empty = mock(HistoryDataReader.class);
        HistoryDataReader populated = mock(HistoryDataReader.class);

        Map<String, Long> result = HistoryDataReaderFallback.firstMatching(
                List.of(unsupported, empty, populated),
                reader -> {
                    if (reader == unsupported) {
                        throw new UnsupportedOperationException();
                    }
                    return reader == empty ? Map.of() : Map.of("ERROR", 3L);
                },
                value -> value != null && !value.isEmpty()
        );

        assertEquals(Map.of("ERROR", 3L), result);
    }

    @Test
    void returnsNullWhenNoReaderProducesMatchingResult() {
        HistoryDataReader unsupported = mock(HistoryDataReader.class);

        String result = HistoryDataReaderFallback.firstMatching(
                List.of(unsupported),
                reader -> {
                    throw new UnsupportedOperationException();
                },
                value -> value != null && !value.isBlank()
        );

        assertNull(result);
    }
}
