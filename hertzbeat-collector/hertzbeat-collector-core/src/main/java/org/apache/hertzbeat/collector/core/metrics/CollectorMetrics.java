package org.apache.hertzbeat.collector.core.metrics;

import org.apache.hertzbeat.common.entity.job.Job;

/**
 * Collector Metrics Interface
 */
public interface CollectorMetrics {
    /**
     * Record collect metrics
     * @param job job
     * @param durationMillis duration
     * @param status status
     */
    void recordCollectMetrics(Job job, long durationMillis, String status);
}
