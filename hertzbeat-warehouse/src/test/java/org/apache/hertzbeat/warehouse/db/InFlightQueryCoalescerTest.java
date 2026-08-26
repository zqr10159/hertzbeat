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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InFlightQueryCoalescerTest {

    private final InFlightQueryCoalescer<String, String> coalescer = new InFlightQueryCoalescer<>();

    @Test
    void sharesOnlyTheCurrentlyRunningQueryForTheSameKey() throws Exception {
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        List<Thread> callers = new ArrayList<>();
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());

        callers.add(Thread.ofVirtual().start(() -> results.add(coalescer.execute("same-query", () -> {
            executions.incrementAndGet();
            queryStarted.countDown();
            try {
                releaseQuery.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return "shared-result";
        }))));

        assertTrue(queryStarted.await(1, TimeUnit.SECONDS));
        List<Thread> followers = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            Thread follower = Thread.ofVirtual().start(() -> results.add(coalescer.execute("same-query", () -> {
                throw new AssertionError("A follower must share the in-flight query");
            })));
            followers.add(follower);
            callers.add(follower);
        }

        assertTrue(awaitWaiting(followers, Duration.ofSeconds(1)));
        assertEquals(1, executions.get());
        releaseQuery.countDown();
        for (Thread caller : callers) {
            caller.join(Duration.ofSeconds(1));
        }

        assertEquals(12, results.size());
        assertEquals(1, executions.get());
        assertEquals("fresh-result", coalescer.execute("same-query", () -> {
            executions.incrementAndGet();
            return "fresh-result";
        }));
        assertEquals(2, executions.get(), "Completed results must not be cached");
    }

    @Test
    void removesFailedQuerySoTheNextCallCanRetry() {
        AtomicInteger executions = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> coalescer.execute("query", () -> {
            executions.incrementAndGet();
            throw new IllegalStateException("backend unavailable");
        }));

        assertEquals("recovered", coalescer.execute("query", () -> {
            executions.incrementAndGet();
            return "recovered";
        }));
        assertEquals(2, executions.get());
    }

    private boolean awaitWaiting(List<Thread> threads, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (threads.stream().allMatch(thread -> thread.getState() == Thread.State.WAITING)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }
}
