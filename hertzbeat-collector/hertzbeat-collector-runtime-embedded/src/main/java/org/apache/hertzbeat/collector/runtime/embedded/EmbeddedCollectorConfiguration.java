package org.apache.hertzbeat.collector.runtime.embedded;

import org.apache.hertzbeat.collector.core.api.CollectorEngine;
import org.apache.hertzbeat.collector.core.dispatch.CommonDispatcher;
import org.apache.hertzbeat.collector.core.dispatch.MetricsCollectorQueue;
import org.apache.hertzbeat.collector.core.dispatch.MetricsTaskDispatch;
import org.apache.hertzbeat.collector.core.dispatch.TimerDispatch;
import org.apache.hertzbeat.collector.core.dispatch.TimerDispatcher;
import org.apache.hertzbeat.collector.core.dispatch.WorkerPool;
import org.apache.hertzbeat.collector.core.dispatch.unit.UnitConvert;
import org.apache.hertzbeat.collector.core.dispatch.unit.impl.DataSizeConvert;
import org.apache.hertzbeat.collector.core.dispatch.unit.impl.TimeLengthConvert;
import org.apache.hertzbeat.collector.core.engine.CollectJobService;
import org.apache.hertzbeat.collector.core.metrics.CollectorMetrics;
import org.apache.hertzbeat.common.entity.job.Metrics;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.apache.hertzbeat.common.timer.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Embedded Collector Configuration
 */
@Configuration
public class EmbeddedCollectorConfiguration {

    @Bean("embeddedWorkerPool")
    @ConditionalOnMissingBean
    public WorkerPool workerPool() {
        return new WorkerPool();
    }

    @Bean("embeddedMetricsCollectorQueue")
    @ConditionalOnMissingBean
    public MetricsCollectorQueue metricsCollectorQueue() {
        return new MetricsCollectorQueue();
    }

    @Bean("embeddedCollectorMetrics")
    @ConditionalOnMissingBean
    public CollectorMetrics collectorMetrics() {
        return null; 
    }

    @Bean("embeddedDataSizeConvert")
    public UnitConvert dataSizeConvert() {
        return new DataSizeConvert();
    }

    @Bean("embeddedTimeLengthConvert")
    public UnitConvert timeLengthConvert() {
        return new TimeLengthConvert();
    }

    @Bean("embeddedMetricsTaskDispatch")
    public ForwardingMetricsTaskDispatch metricsTaskDispatch() {
        return new ForwardingMetricsTaskDispatch();
    }

    @Bean
    public EmbeddedResultSender embeddedResultSender() {
        return new EmbeddedResultSender();
    }

    @Bean("embeddedTimerDispatch")
    @ConditionalOnMissingBean
    public TimerDispatch timerDispatch(ForwardingMetricsTaskDispatch metricsTaskDispatch) {
        return new TimerDispatcher(metricsTaskDispatch);
    }

    @Bean("embeddedCommonDispatcher")
    @ConditionalOnMissingBean
    public CommonDispatcher commonDispatcher(MetricsCollectorQueue metricsCollectorQueue,
                                             TimerDispatch timerDispatch,
                                             EmbeddedResultSender embeddedResultSender,
                                             WorkerPool workerPool,
                                             List<UnitConvert> unitConvertList,
                                             CollectorMetrics collectorMetrics,
                                             ForwardingMetricsTaskDispatch forwardingMetricsTaskDispatch) {
        CommonDispatcher dispatcher = new CommonDispatcher(metricsCollectorQueue, timerDispatch, embeddedResultSender, 
                                    workerPool, "embedded-collector", unitConvertList, collectorMetrics);
        forwardingMetricsTaskDispatch.setDelegate(dispatcher);
        return dispatcher;
    }
    
    @Bean("embeddedCollectorEngine")
    public CollectorEngine collectorEngine(TimerDispatch timerDispatch, 
                                           WorkerPool workerPool, 
                                           EmbeddedResultSender embeddedResultSender) {
        return new CollectJobService(timerDispatch, workerPool, embeddedResultSender, "embedded-collector");
    }

    static class ForwardingMetricsTaskDispatch implements MetricsTaskDispatch {
        private MetricsTaskDispatch delegate;

        public void setDelegate(MetricsTaskDispatch delegate) {
            this.delegate = delegate;
        }

        @Override
        public void dispatchMetricsTask(Timeout timeout) {
            if (delegate != null) {
                delegate.dispatchMetricsTask(timeout);
            }
        }

        @Override
        public void dispatchCollectData(Timeout timeout, Metrics metrics, CollectRep.MetricsData metricsData) {
            if (delegate != null) {
                delegate.dispatchCollectData(timeout, metrics, metricsData);
            }
        }

        @Override
        public void dispatchCollectData(Timeout timeout, Metrics metrics, List<CollectRep.MetricsData> metricsDataList) {
            if (delegate != null) {
                delegate.dispatchCollectData(timeout, metrics, metricsDataList);
            }
        }
    }
}
