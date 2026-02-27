package org.apache.hertzbeat.collector.runtime.embedded;

import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.springframework.context.ApplicationEvent;

/**
 * Collect Result Event
 */
public class CollectResultEvent extends ApplicationEvent {

    private final CollectRep.MetricsData metricsData;

    public CollectResultEvent(Object source, CollectRep.MetricsData metricsData) {
        super(source);
        this.metricsData = metricsData;
    }

    public CollectRep.MetricsData getMetricsData() {
        return metricsData;
    }
}
