package org.apache.hertzbeat.collector.core.dispatch;

import org.apache.hertzbeat.common.entity.message.CollectRep;
import java.util.List;

/**
 * One-time synchronous task listener, asynchronous task does not need listener
 */
public interface CollectResponseEventListener {
    /**
     * response collect data
     * @param responseMetrics collect data
     */
    void response(List<CollectRep.MetricsData> responseMetrics);
}
