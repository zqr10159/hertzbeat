package org.apache.hertzbeat.collector.core.engine;

import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.collector.core.api.CollectorEngine;
import org.apache.hertzbeat.collector.core.dispatch.TimerDispatch;
import org.apache.hertzbeat.collector.core.dispatch.WorkerPool;
import org.apache.hertzbeat.collector.core.dispatch.CollectResponseEventListener;
import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.message.CollectRep;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Collection job management provides api interface
 */
@Slf4j
public class CollectJobService implements CollectorEngine {

    private final TimerDispatch timerDispatch;

    private final WorkerPool workerPool;
    
    private final CollectResultSender collectResultSender;

    private final String collectorIdentity;

    public CollectJobService(TimerDispatch timerDispatch, WorkerPool workerPool, CollectResultSender collectResultSender, String collectorIdentity) {
        this.timerDispatch = timerDispatch;
        this.workerPool = workerPool;
        this.collectResultSender = collectResultSender;
        this.collectorIdentity = collectorIdentity;
    }

    /**
     * Execute a one-time collection task and get the collected data response
     *
     * @param job Collect task details
     * @return Collection results
     */
    public List<CollectRep.MetricsData> collectSyncJobData(Job job) {
        final List<CollectRep.MetricsData> metricsData = new LinkedList<>();
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        CollectResponseEventListener listener = new CollectResponseEventListener() {
            @Override
            public void response(List<CollectRep.MetricsData> responseMetrics) {
                if (responseMetrics != null) {
                    metricsData.addAll(responseMetrics);
                }
                countDownLatch.countDown();
            }
        };
        timerDispatch.addJob(job, listener);
        try {
            countDownLatch.await(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.info("The sync task runs for 120 seconds with no response and returns");
        }
        return metricsData;
    }

    /**
     * Execute a one-time collection task and send the collected data response
     *
     * @param oneTimeJob Collect task details
     */
    public void collectSyncOneTimeJobData(Job oneTimeJob) {
        workerPool.executeJob(() -> {
            List<CollectRep.MetricsData> metricsDataList = this.collectSyncJobData(oneTimeJob);
            // Send result via sender
            for (CollectRep.MetricsData data : metricsDataList) {
                collectResultSender.sendCollectData(data);
            }
        });
    }

    /**
     * Issue periodic asynchronous collection tasks
     *
     * @param job Collect task details
     */
    public void addAsyncCollectJob(Job job) {
        timerDispatch.addJob(job.clone(), null);
    }

    /**
     * Cancel periodic asynchronous collection tasks
     *
     * @param jobId Job ID
     */
    public void cancelAsyncCollectJob(Long jobId) {
        if (jobId != null) {
            timerDispatch.deleteJob(jobId, true);
        }
    }

    @Override
    public void start() {
        timerDispatch.goOnline();
    }

    @Override
    public long submitAsync(Job job) {
        addAsyncCollectJob(job);
        return job.getId();
    }

    @Override
    public List<CollectRep.MetricsData> collectOnce(Job job) {
        return collectSyncJobData(job);
    }

    @Override
    public void cancel(long jobId) {
        cancelAsyncCollectJob(jobId);
    }

    @Override
    public void shutdown() {
        timerDispatch.goOffline();
    }
}
