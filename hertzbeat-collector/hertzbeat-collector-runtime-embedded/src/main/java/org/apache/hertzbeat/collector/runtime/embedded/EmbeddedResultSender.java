package org.apache.hertzbeat.collector.runtime.embedded;

import org.apache.hertzbeat.collector.core.engine.CollectResultSender;
import org.apache.hertzbeat.common.entity.log.LogEntry;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.apache.hertzbeat.common.queue.CommonDataQueue;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

import java.util.List;

/**
 * Embedded Result Sender
 */
public class EmbeddedResultSender implements CollectResultSender, CommonDataQueue, ApplicationEventPublisherAware {

    private ApplicationEventPublisher eventPublisher;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    @Override
    public void sendCollectData(CollectRep.MetricsData metricsData) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new CollectResultEvent(this, metricsData));
        }
    }

    @Override
    public CollectRep.MetricsData pollMetricsDataToAlerter() throws InterruptedException {
        return null;
    }

    @Override
    public CollectRep.MetricsData pollMetricsDataToStorage() throws InterruptedException {
        return null;
    }

    @Override
    public CollectRep.MetricsData pollServiceDiscoveryData() throws InterruptedException {
        return null;
    }

    @Override
    public void sendMetricsData(CollectRep.MetricsData metricsData) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new CollectResultEvent(this, metricsData));
        }
    }

    @Override
    public void sendMetricsDataToStorage(CollectRep.MetricsData metricsData) {
        // embedded mode default not support storage
    }

    @Override
    public void sendServiceDiscoveryData(CollectRep.MetricsData metricsData) {
        // reuse same event or different? Use same for now
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new CollectResultEvent(this, metricsData));
        }
    }

    @Override
    public void sendLogEntry(LogEntry logEntry) {
        
    }

    @Override
    public LogEntry pollLogEntry() throws InterruptedException {
        return null;
    }

    @Override
    public void sendLogEntryToStorage(LogEntry logEntry) {

    }

    @Override
    public LogEntry pollLogEntryToStorage() throws InterruptedException {
        return null;
    }

    @Override
    public void sendLogEntryToAlertBatch(List<LogEntry> logEntries) {

    }

    @Override
    public List<LogEntry> pollLogEntryToAlertBatch(int maxBatchSize) throws InterruptedException {
        return null;
    }

    @Override
    public void sendLogEntryToStorageBatch(List<LogEntry> logEntries) {

    }

    @Override
    public List<LogEntry> pollLogEntryToStorageBatch(int maxBatchSize) throws InterruptedException {
        return null;
    }
}
