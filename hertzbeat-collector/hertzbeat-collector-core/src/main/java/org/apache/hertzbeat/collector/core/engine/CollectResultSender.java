package org.apache.hertzbeat.collector.core.engine;

import org.apache.hertzbeat.common.entity.message.CollectRep;

/**
 * Interface for sending collection results.
 * Implementations will handle the transport (e.g., Netty, Embedded).
 */
public interface CollectResultSender {
    void sendCollectData(CollectRep.MetricsData metricsData);
}
