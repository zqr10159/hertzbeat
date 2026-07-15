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

import jakarta.annotation.PreDestroy;
import java.time.Duration;
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
        this.lanes = Map.of(
                "metrics", new Lane("metrics", metricsMaxConcurrentRequests, queueCapacity,
                        maxQueueWait, queryTimeout),
                "logs", new Lane("logs", logsMaxConcurrentRequests, queueCapacity,
                        maxQueueWait, queryTimeout),
                "traces", new Lane("traces", tracesMaxConcurrentRequests, queueCapacity,
                        maxQueueWait, queryTimeout),
                "topology", new Lane("topology", topologyMaxConcurrentRequests, queueCapacity,
                        maxQueueWait, queryTimeout));
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

        private Lane(String signal, int maxConcurrentRequests, int queueCapacity,
                     Duration maxQueueWait, Duration queryTimeout) {
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
        }

        private <T> T execute(Supplier<T> operation, String workspaceId) {
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
                throw failure(ObservabilityQueryAdmissionException.Reason.OVERLOADED, exception);
            }
            try {
                if (queueEnabled && !started.await(maxQueueWaitNanos, TimeUnit.NANOSECONDS)) {
                    cancel(future);
                    throw failure(ObservabilityQueryAdmissionException.Reason.OVERLOADED, null);
                }
                return future.get(queryTimeoutNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                cancel(future);
                Thread.currentThread().interrupt();
                throw failure(ObservabilityQueryAdmissionException.Reason.CANCELLED, exception);
            } catch (TimeoutException exception) {
                cancel(future);
                throw failure(ObservabilityQueryAdmissionException.Reason.TIMED_OUT, exception);
            } catch (ExecutionException exception) {
                throw propagate(exception.getCause());
            }
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
    }
}
