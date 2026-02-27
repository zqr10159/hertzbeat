package org.apache.hertzbeat.collector.core.dispatch;

import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.message.CollectRep;

import java.util.List;

/**
 * timer dispatch service
 */
public interface TimerDispatch {

    /**
     * Add new job
     *
     * @param addJob        job
     * @param eventListener One-time synchronous task listener, asynchronous task does not need listener
     */
    void addJob(Job addJob, CollectResponseEventListener eventListener);

    /**
     * Delete existing job
     * @param jobId    jobId
     * @param isCyclic Whether it is a periodic task, true is, false is a temporary task
     */
    void deleteJob(long jobId, boolean isCyclic);
    
    /**
     * job dispatcher go online
     */
    void goOnline();
    
    /**
     * job dispatcher go offline
     */
    void goOffline();

    /**
     * response sync collect task data
     * @param jobId            jobId
     * @param metricsDataTemps collect data
     */
    void responseSyncJobData(long jobId, List<CollectRep.MetricsData> metricsDataTemps);

    /**
     * cyclic job
     * @param timerTask timer task
     */
    void cyclicJob(WheelTimerTask timerTask);

    /**
     * cyclic job
     * @param timerTask timer task
     * @param interval interval
     * @param timeUnit time unit
     */
    void cyclicJob(WheelTimerTask timerTask, long interval, java.util.concurrent.TimeUnit timeUnit);
}
