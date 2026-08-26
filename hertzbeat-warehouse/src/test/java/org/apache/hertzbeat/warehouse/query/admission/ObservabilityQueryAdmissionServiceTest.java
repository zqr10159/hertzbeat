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

package org.apache.hertzbeat.warehouse.query.admission;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hertzbeat.common.observability.gateway.AuthTokenRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObservabilityQueryAdmissionServiceTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ObservabilityQueryAdmissionService service;

    @AfterEach
    void closeService() {
        AuthTokenRequestContext.clear();
        if (service != null) {
            service.close();
        }
        meterRegistry.clear();
    }

    @Test
    void propagatesAndClearsWorkspaceContext() {
        service = service(1, 0, Duration.ZERO, Duration.ofSeconds(5));
        AuthTokenRequestContext.bindWorkspaceId("workspace-a");

        assertEquals("workspace-a", service.execute(
                "logs", AuthTokenRequestContext::currentWorkspaceId));
        AuthTokenRequestContext.clear();
        assertEquals(null, service.execute(
                "logs", AuthTokenRequestContext::currentWorkspaceId));
    }

    @Test
    void rejectsFullLaneQuicklyAndRecoversAfterActiveQueryCompletes() throws Exception {
        service = service(1, 0, Duration.ZERO, Duration.ofSeconds(5));
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        Thread activeRequest = startBlockingQuery("metrics", queryStarted, releaseQuery);
        assertTrue(queryStarted.await(1, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        ObservabilityQueryAdmissionException exception = assertThrows(
                ObservabilityQueryAdmissionException.class,
                () -> service.execute("metrics", () -> "rejected"));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertEquals(ObservabilityQueryAdmissionException.Reason.OVERLOADED, exception.getReason());
        assertEquals("metrics", exception.getSignal());
        assertTrue(elapsedMillis < 250, "A saturated query lane must reject promptly");

        releaseQuery.countDown();
        activeRequest.join(1_000);
        assertFalse(activeRequest.isAlive());
        assertEquals("recovered", service.execute("metrics", () -> "recovered"));
    }

    @Test
    void removesQueryThatExceedsShortQueueWait() throws Exception {
        service = service(1, 1, Duration.ofMillis(50), Duration.ofSeconds(5));
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        Thread activeRequest = startBlockingQuery("logs", queryStarted, releaseQuery);
        assertTrue(queryStarted.await(1, TimeUnit.SECONDS));

        AtomicBoolean queuedOperationRan = new AtomicBoolean();
        long startedAt = System.nanoTime();
        ObservabilityQueryAdmissionException exception = assertThrows(
                ObservabilityQueryAdmissionException.class,
                () -> service.execute("logs", () -> {
                    queuedOperationRan.set(true);
                    return "late";
                }));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertEquals(ObservabilityQueryAdmissionException.Reason.OVERLOADED, exception.getReason());
        assertTrue(elapsedMillis >= 25, "A queued query should get a short opportunity to start");
        assertTrue(elapsedMillis < 500, "Queue wait must remain bounded");
        releaseQuery.countDown();
        activeRequest.join(1_000);
        assertFalse(queuedOperationRan.get(), "An expired queued query must not execute later");
    }

    @Test
    void interruptsTimedOutWorkAndReleasesCapacity() {
        service = service(1, 0, Duration.ZERO, Duration.ofMillis(75));
        AtomicBoolean interrupted = new AtomicBoolean();

        ObservabilityQueryAdmissionException exception = assertThrows(
                ObservabilityQueryAdmissionException.class,
                () -> service.execute("traces", () -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException interruptedException) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                    return "late";
                }));

        assertEquals(ObservabilityQueryAdmissionException.Reason.TIMED_OUT, exception.getReason());
        await().atMost(Duration.ofSeconds(1)).untilTrue(interrupted);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(
                () -> assertEquals("recovered", service.execute("traces", () -> "recovered")));
        assertEquals(1, meterRegistry.get("hertzbeat.observability.query.duration")
                .tags("signal", "traces", "outcome", "timed_out").timer().count());
    }

    @Test
    void isolatesMetricsLogsTracesAndTopologyLanes() throws Exception {
        service = service(1, 0, Duration.ZERO, Duration.ofSeconds(5));
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        Thread activeRequest = startBlockingQuery("metrics", queryStarted, releaseQuery);
        assertTrue(queryStarted.await(1, TimeUnit.SECONDS));

        assertEquals("logs", service.execute("logs", () -> "logs"));
        assertEquals("traces", service.execute("traces", () -> "traces"));
        assertEquals("topology", service.execute("topology", () -> "topology"));

        releaseQuery.countDown();
        activeRequest.join(1_000);
        assertFalse(activeRequest.isAlive());
    }

    @Test
    void interruptionCancelsTheUnderlyingQuery() throws Exception {
        service = service(1, 0, Duration.ZERO, Duration.ofSeconds(5));
        CountDownLatch queryStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = Thread.ofVirtual().start(() -> {
            try {
                service.execute("topology", () -> {
                    queryStarted.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException interruptedException) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        assertTrue(queryStarted.await(1, TimeUnit.SECONDS));

        request.interrupt();
        request.join(1_000);

        assertFalse(request.isAlive());
        ObservabilityQueryAdmissionException exception =
                (ObservabilityQueryAdmissionException) failure.get();
        assertEquals(ObservabilityQueryAdmissionException.Reason.CANCELLED, exception.getReason());
        await().atMost(Duration.ofSeconds(1)).untilTrue(interrupted);
        assertEquals(1, meterRegistry.get("hertzbeat.observability.query.duration")
                .tags("signal", "topology", "outcome", "cancelled").timer().count());
    }

    @Test
    void recordsStorageFailureOutcomeWithoutChangingTheException() {
        service = service(1, 0, Duration.ZERO, Duration.ofSeconds(5));
        IllegalStateException failure = new IllegalStateException("storage unavailable");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> service.execute("logs", () -> {
                    throw failure;
                }));

        assertEquals(failure, actual);
        assertEquals(1, meterRegistry.get("hertzbeat.observability.query.duration")
                .tags("signal", "logs", "outcome", "error").timer().count());
    }

    @Test
    void exposesBoundedLaneOccupancyAndOutcomes() throws Exception {
        service = service(1, 1, Duration.ofSeconds(5), Duration.ofSeconds(5));
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        Thread activeRequest = startBlockingQuery("metrics", activeStarted, releaseActive);
        assertTrue(activeStarted.await(1, TimeUnit.SECONDS));

        CountDownLatch queuedCompleted = new CountDownLatch(1);
        Thread queuedRequest = Thread.ofVirtual().start(() -> {
            service.execute("metrics", () -> "queued");
            queuedCompleted.countDown();
        });
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertEquals(1.0, meterRegistry.get("hertzbeat.observability.query.active")
                    .tag("signal", "metrics").gauge().value());
            assertEquals(1.0, meterRegistry.get("hertzbeat.observability.query.queued")
                    .tag("signal", "metrics").gauge().value());
        });

        assertThrows(ObservabilityQueryAdmissionException.class,
                () -> service.execute("metrics", () -> "rejected"));
        assertEquals(1, meterRegistry.get("hertzbeat.observability.query.duration")
                .tags("signal", "metrics", "outcome", "rejected").timer().count());

        releaseActive.countDown();
        activeRequest.join(1_000);
        assertTrue(queuedCompleted.await(1, TimeUnit.SECONDS));
        queuedRequest.join(1_000);
        assertEquals(2, meterRegistry.get("hertzbeat.observability.query.duration")
                .tags("signal", "metrics", "outcome", "success").timer().count());
        assertEquals(2, meterRegistry.get("hertzbeat.observability.query.queue.wait")
                .tag("signal", "metrics").timer().count());
        assertEquals(0.0, meterRegistry.get("hertzbeat.observability.query.active")
                .tag("signal", "metrics").gauge().value());
        assertEquals(0.0, meterRegistry.get("hertzbeat.observability.query.queued")
                .tag("signal", "metrics").gauge().value());
    }

    private ObservabilityQueryAdmissionService service(int maxConcurrentRequests,
                                                        int queueCapacity,
                                                        Duration maxQueueWait,
                                                        Duration queryTimeout) {
        return new ObservabilityQueryAdmissionService(
                meterRegistry,
                maxConcurrentRequests, maxConcurrentRequests, maxConcurrentRequests, maxConcurrentRequests,
                queueCapacity, maxQueueWait, queryTimeout);
    }

    private Thread startBlockingQuery(String signal,
                                      CountDownLatch queryStarted,
                                      CountDownLatch releaseQuery) {
        return Thread.ofVirtual().start(() -> service.execute(signal, () -> {
            queryStarted.countDown();
            try {
                releaseQuery.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Test query interrupted", exception);
            }
            return "completed";
        }));
    }
}
