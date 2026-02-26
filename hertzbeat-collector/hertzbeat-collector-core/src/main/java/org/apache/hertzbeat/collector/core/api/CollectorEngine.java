package org.apache.hertzbeat.collector.core.api;

import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import java.util.List;

/**
 * Collector engine interface
 */
public interface CollectorEngine {
    /**
     * Start the collector engine
     */
    void start();

    /**
     * Submit an asynchronous collection job
     * @param job Collect task details
     * @return Job ID
     */
    long submitAsync(Job job);

    /**
     * Collect metrics once synchronously
     * @param job Collect task details
     * @return Collection results
     */
    List<CollectRep.MetricsData> collectOnce(Job job);

    /**
     * Cancel a collection job
     * @param jobId Job ID
     */
    void cancel(long jobId);

    /**
     * Shutdown the collector engine
     */
    void shutdown();
}
