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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.hertzbeat.common.observability.gateway.AuthTokenRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Isolates observability reads in bounded, cancellation-aware signal lanes.
 */
@Service
public class ObservabilityQueryAdmissionService implements AutoCloseable {

    private final Map<String, Lane> lanes;

    @Autowired
    public ObservabilityQueryAdmissionService(
            MeterRegistry meterRegistry,
            @Value("${hertzbeat.observability.query.admission.metrics.max-concurrent-requests:8}")
            int metricsMaxConcurrentRequests,
            @Value("${hertzbeat.observability.query.admission.logs.max-concurrent-requests:8}")
            int logsMaxConcurrentRequests,
            @Value("${hertzbeat.observability.query.admission.traces.max-concurrent-requests:8}")
            int tracesMaxConcurrentRequests,
            @Value("${hertzbeat.observability.query.admission.topology.max-concurrent-requests:4}")
            int topologyMaxConcurrentRequests,
            @Value("${hertzbeat.observability.query.admission.queue-capacity:8}")
            int queueCapacity,
            @Value("${hertzbeat.observability.query.admission.max-queue-wait:100ms}")
            Duration maxQueueWait,
            @Value("${hertzbeat.observability.query.admission.timeout:5s}")
            Duration queryTimeout) {
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.lanes = Map.of(
                "metrics", new Lane("metrics", metricsMaxConcurrentRequests, queueCapacity,
                        maxQueueWait, queryTimeout, meterRegistry),
                "logs", new Lane("logs", logsMaxConcurrentRequests, queueCapacity,
                        maxQueueWait, queryTimeout, meterRegistry),
                "traces", new Lane("traces", tracesMaxConcurrentRequests, queueCapacity,
                        maxQueueWait, queryTimeout, meterRegistry),
                "topology", new Lane("topology", topologyMaxConcurrentRequests, queueCapacity,
                        maxQueueWait, queryTimeout, meterRegistry));
    }

    /**
     * Execute a query in the lane dedicated to its signal.
     *
     * @param signal query signal
     * @param operation query operation
     * @param <T> result type
     * @return query result
     */
    public <T> T execute(String signal, Supplier<T> operation) {
        Lane lane = lanes.get(signal);
        if (lane == null) {
            throw new IllegalArgumentException("Unsupported observability query signal: " + signal);
        }
        return lane.execute(
                Objects.requireNonNull(operation, "operation"),
                AuthTokenRequestContext.currentWorkspaceId());
    }

    @Override
    @PreDestroy
    public void close() {
        lanes.values().forEach(Lane::close);
    }

    private static final class Lane {

        private final String signal;
        private final ThreadPoolExecutor executor;
        private final boolean queueEnabled;
        private final long maxQueueWaitNanos;
        private final long queryTimeoutNanos;
        private final Timer queueWaitTimer;
        private final Map<Outcome, Timer> durationTimers;

        private Lane(String signal, int maxConcurrentRequests, int queueCapacity,
                     Duration maxQueueWait, Duration queryTimeout, MeterRegistry meterRegistry) {
            if (maxConcurrentRequests <= 0) {
                throw new IllegalArgumentException("maxConcurrentRequests must be greater than zero");
            }
            if (queueCapacity < 0) {
                throw new IllegalArgumentException("queueCapacity must not be negative");
            }
            this.signal = signal;
            this.queueEnabled = queueCapacity > 0;
            this.maxQueueWaitNanos = durationNanos(maxQueueWait, true, "maxQueueWait");
            this.queryTimeoutNanos = durationNanos(queryTimeout, false, "queryTimeout");
            this.executor = new ThreadPoolExecutor(
                    maxConcurrentRequests,
                    maxConcurrentRequests,
                    0L,
                    TimeUnit.MILLISECONDS,
                    workQueue(queueCapacity),
                    threadFactory(signal),
                    new ThreadPoolExecutor.AbortPolicy());
            Gauge.builder("hertzbeat.observability.query.active", executor, ThreadPoolExecutor::getActiveCount)
                    .description("Current active observability queries")
                    .tag("signal", signal)
                    .register(meterRegistry);
            Gauge.builder("hertzbeat.observability.query.queued", executor,
                            currentExecutor -> currentExecutor.getQueue().size())
                    .description("Current queued observability queries")
                    .tag("signal", signal)
                    .register(meterRegistry);
            this.queueWaitTimer = Timer.builder("hertzbeat.observability.query.queue.wait")
                    .description("Time accepted observability queries wait before execution")
                    .tag("signal", signal)
                    .register(meterRegistry);
            this.durationTimers = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                durationTimers.put(outcome, Timer.builder("hertzbeat.observability.query.duration")
                        .description("End-to-end observability query duration")
                        .tags("signal", signal, "outcome", outcome.tagValue)
                        .register(meterRegistry));
            }
        }

        private <T> T execute(Supplier<T> operation, String workspaceId) {
            long requestStartedNanos = System.nanoTime();
            CountDownLatch started = new CountDownLatch(1);
            Future<T> future;
            try {
                future = executor.submit(() -> {
                    started.countDown();
                    AuthTokenRequestContext.bindWorkspaceId(workspaceId);
                    try {
                        return operation.get();
                    } finally {
                        AuthTokenRequestContext.clear();
                    }
                });
            } catch (RejectedExecutionException exception) {
                recordDuration(Outcome.REJECTED, requestStartedNanos);
                throw failure(ObservabilityQueryAdmissionException.Reason.OVERLOADED, exception);
            }
            long queuedAtNanos = System.nanoTime();
            try {
                if (queueEnabled && !started.await(maxQueueWaitNanos, TimeUnit.NANOSECONDS)) {
                    recordQueueWait(queuedAtNanos);
                    cancel(future);
                    recordDuration(Outcome.REJECTED, requestStartedNanos);
                    throw failure(ObservabilityQueryAdmissionException.Reason.OVERLOADED, null);
                }
                recordQueueWait(queuedAtNanos);
                T result = future.get(queryTimeoutNanos, TimeUnit.NANOSECONDS);
                recordDuration(Outcome.SUCCESS, requestStartedNanos);
                return result;
            } catch (InterruptedException exception) {
                cancel(future);
                Thread.currentThread().interrupt();
                recordDuration(Outcome.CANCELLED, requestStartedNanos);
                throw failure(ObservabilityQueryAdmissionException.Reason.CANCELLED, exception);
            } catch (TimeoutException exception) {
                cancel(future);
                recordDuration(Outcome.TIMED_OUT, requestStartedNanos);
                throw failure(ObservabilityQueryAdmissionException.Reason.TIMED_OUT, exception);
            } catch (ExecutionException exception) {
                recordDuration(Outcome.ERROR, requestStartedNanos);
                throw propagate(exception.getCause());
            }
        }

        private void recordQueueWait(long queuedAtNanos) {
            queueWaitTimer.record(System.nanoTime() - queuedAtNanos, TimeUnit.NANOSECONDS);
        }

        private void recordDuration(Outcome outcome, long requestStartedNanos) {
            durationTimers.get(outcome).record(
                    System.nanoTime() - requestStartedNanos, TimeUnit.NANOSECONDS);
        }

        private void cancel(Future<?> future) {
            future.cancel(true);
            if (future instanceof Runnable runnable) {
                executor.remove(runnable);
            }
        }

        private RuntimeException failure(ObservabilityQueryAdmissionException.Reason reason, Throwable cause) {
            return new ObservabilityQueryAdmissionException(signal, reason, cause);
        }

        private RuntimeException propagate(Throwable cause) {
            if (cause instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return new IllegalStateException("Observability " + signal + " query failed.", cause);
        }

        private void close() {
            executor.shutdownNow();
        }

        private static BlockingQueue<Runnable> workQueue(int queueCapacity) {
            return queueCapacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queueCapacity, true);
        }

        private static ThreadFactory threadFactory(String signal) {
            AtomicInteger threadIndex = new AtomicInteger();
            return task -> Thread.ofPlatform()
                    .daemon(true)
                    .name("hertzbeat-" + signal + "-query-" + threadIndex.incrementAndGet())
                    .unstarted(task);
        }

        private static long durationNanos(Duration duration, boolean allowZero, String propertyName) {
            if (duration == null || duration.isNegative() || (!allowZero && duration.isZero())) {
                throw new IllegalArgumentException(propertyName + " must be "
                        + (allowZero ? "zero or positive" : "greater than zero"));
            }
            try {
                return duration.toNanos();
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }

        private enum Outcome {
            SUCCESS("success"),
            ERROR("error"),
            REJECTED("rejected"),
            TIMED_OUT("timed_out"),
            CANCELLED("cancelled");

            private final String tagValue;

            Outcome(String tagValue) {
                this.tagValue = tagValue;
            }
        }
    }
}
