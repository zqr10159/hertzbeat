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

package org.apache.hertzbeat.warehouse.db;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Shares an identical read only while that read is still in progress.
 * Completed values and failures are removed immediately and are never cached.
 *
 * @param <K> query key type
 * @param <V> query result type
 */
final class InFlightQueryCoalescer<K, V> {

    private final ConcurrentMap<K, CompletableFuture<V>> queries = new ConcurrentHashMap<>();

    V execute(K key, Supplier<V> operation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<V> candidate = new CompletableFuture<>();
        CompletableFuture<V> existing = queries.putIfAbsent(key, candidate);
        if (existing != null) {
            return await(existing);
        }
        try {
            V result = operation.get();
            candidate.complete(result);
            queries.remove(key, candidate);
            return result;
        } catch (Throwable failure) {
            candidate.completeExceptionally(failure);
            queries.remove(key, candidate);
            throw propagate(failure);
        }
    }

    private V await(CompletableFuture<V> query) {
        try {
            return query.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an in-flight query", exception);
        } catch (ExecutionException exception) {
            throw propagate(exception.getCause());
        }
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("In-flight query failed", failure);
    }
}
