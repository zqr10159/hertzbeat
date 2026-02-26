package org.apache.hertzbeat.collector.core.dispatch;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.collector.core.constants.ScheduleTypeEnum;
import org.apache.hertzbeat.collector.core.timer.WheelTimerTask;
import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.job.Metrics;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.apache.hertzbeat.common.timer.HashedWheelTimer;
import org.apache.hertzbeat.common.timer.Timeout;
import org.apache.hertzbeat.common.timer.Timer;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * job timer dispatcher
 */
@Slf4j
public class TimerDispatcher implements TimerDispatch {

    /**
     * time round schedule
     */
    private final Timer wheelTimer;
    /**
     * Existing periodic scheduled tasks
     */
    private final Map<Long, Timeout> currentCyclicTaskMap;
    /**
     * Existing temporary scheduled tasks
     */
    private final Map<Long, Timeout> currentTempTaskMap;
    /**
     * One-time task response listener holds
     * jobId - listener
     */
    private final Map<Long, CollectResponseEventListener> eventListeners;

    private final MetricsTaskDispatch metricsTaskDispatch;

    /**
     * is dispatcher online running
     */
    private final AtomicBoolean started;

    private static final CronParser CRON_PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    public TimerDispatcher(MetricsTaskDispatch metricsTaskDispatch) {
        this.metricsTaskDispatch = metricsTaskDispatch;
        this.wheelTimer = new HashedWheelTimer(r -> {
            Thread ret = new Thread(r, "wheelTimer");
            ret.setDaemon(true);
            return ret;
        }, 1, TimeUnit.SECONDS, 512);
        this.currentCyclicTaskMap = new ConcurrentHashMap<>(8);
        this.currentTempTaskMap = new ConcurrentHashMap<>(8);
        this.eventListeners = new ConcurrentHashMap<>(8);
        this.started = new AtomicBoolean(true);
    }

    @Override
    public void addJob(Job addJob, CollectResponseEventListener eventListener) {
        if (!this.started.get()) {
            log.warn("Collector is offline, can not dispatch collect jobs.");
            return;
        }
        WheelTimerTask timerJob = new WheelTimerTask(addJob, metricsTaskDispatch);
        if (addJob.isCyclic()) {
            Long nextExecutionTime = getNextExecutionInterval(addJob);
            Timeout timeout = wheelTimer.newTimeout(timerJob, nextExecutionTime, TimeUnit.SECONDS);
            currentCyclicTaskMap.put(addJob.getId(), timeout);
        } else {
            for (Metrics metric : addJob.getMetrics()) {
                metric.setInterval(0L);
            }
            addJob.setIntervals(new ConcurrentLinkedDeque<>(List.of(0L)));
            Timeout timeout = wheelTimer.newTimeout(timerJob, addJob.getInterval(), TimeUnit.SECONDS);
            currentTempTaskMap.put(addJob.getId(), timeout);
            eventListeners.put(addJob.getId(), eventListener);
        }
    }
    
    public void cyclicJob(WheelTimerTask timerTask, long interval, TimeUnit timeUnit) {
        if (!this.started.get()) {
            log.warn("Collector is offline, can not dispatch collect jobs.");
            return;
        }
        Long jobId = timerTask.getJob().getId();
        // whether is the job has been canceled
        if (currentCyclicTaskMap.containsKey(jobId)) {
            Timeout timeout = wheelTimer.newTimeout(timerTask, interval, TimeUnit.SECONDS);
            currentCyclicTaskMap.put(timerTask.getJob().getId(), timeout);
        }
    }

    public void cyclicJob(WheelTimerTask timerTask) {
        Job job = timerTask.getJob();
        Long nextExecutionTime = getNextExecutionInterval(job);
        cyclicJob(timerTask, nextExecutionTime, TimeUnit.SECONDS);
    }

    @Override
    public void deleteJob(long jobId, boolean isCyclic) {
        if (isCyclic) {
            Timeout timeout = currentCyclicTaskMap.remove(jobId);
            if (timeout != null) {
                timeout.cancel();
            }
        } else {
            Timeout timeout = currentTempTaskMap.remove(jobId);
            if (timeout != null) {
                timeout.cancel();
            }
        }
    }

    @Override
    public void goOnline() {
        currentCyclicTaskMap.forEach((key, value) -> value.cancel());
        currentCyclicTaskMap.clear();
        currentTempTaskMap.forEach((key, value) -> value.cancel());
        currentTempTaskMap.clear();
        started.set(true);
    }

    @Override
    public void goOffline() {
        started.set(false);
        currentCyclicTaskMap.forEach((key, value) -> value.cancel());
        currentCyclicTaskMap.clear();
        currentTempTaskMap.forEach((key, value) -> value.cancel());
        currentTempTaskMap.clear();
    }


    @Override
    public void responseSyncJobData(long jobId, List<CollectRep.MetricsData> metricsDataTemps) {
        currentTempTaskMap.remove(jobId);
        CollectResponseEventListener eventListener = eventListeners.remove(jobId);
        if (eventListener != null) {
            eventListener.response(metricsDataTemps);
        }
    }

    public void destroy() {
        this.wheelTimer.stop();
    }

    public Long getNextExecutionInterval(Job job) {
        if (ScheduleTypeEnum.CRON.getType().equals(job.getScheduleType()) && job.getCronExpression() != null && !job.getCronExpression().isEmpty()) {
            try {
                ExecutionTime executionTime = ExecutionTime.forCron(CRON_PARSER.parse(job.getCronExpression()));
                ZonedDateTime now = ZonedDateTime.now();
                long delay = executionTime.timeToNextExecution(now).map(Duration::toMillis).orElse(0L);
                return Math.max(0, delay / 1000);
            } catch (Exception e) {
                log.error("Invalid cron expression: {}", job.getCronExpression(), e);
                return job.getInterval();
            }
        } else {
            if (job.getDispatchTime() > 0) {
                long spendTime = System.currentTimeMillis() - job.getDispatchTime();
                long intervalMs = job.getInterval() * 1000 - spendTime;
                return Math.max(0, intervalMs / 1000);
            }
            return job.getInterval();
        }
    }

}
