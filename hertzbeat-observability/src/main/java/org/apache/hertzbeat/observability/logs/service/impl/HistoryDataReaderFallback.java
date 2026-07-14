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

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.hertzbeat.warehouse.store.history.tsdb.HistoryDataReader;

/**
 * Selects the first history reader result accepted by a query-specific policy.
 */
final class HistoryDataReaderFallback {

    private HistoryDataReaderFallback() {
    }

    static <T> T firstMatching(List<HistoryDataReader> readers,
                               Function<HistoryDataReader, T> query,
                               Predicate<T> resultPolicy) {
        if (readers == null || readers.isEmpty()) {
            return null;
        }
        for (HistoryDataReader reader : readers) {
            try {
                T result = query.apply(reader);
                if (resultPolicy.test(result)) {
                    return result;
                }
            } catch (UnsupportedOperationException ex) {
                // Continue with the next configured history store.
            }
        }
        return null;
    }
}
