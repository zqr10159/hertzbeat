package org.apache.hertzbeat.collector.core.timer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.collector.core.dispatch.DispatchConstants;
import org.apache.hertzbeat.collector.core.dispatch.MetricsTaskDispatch;
import org.apache.hertzbeat.collector.core.util.CollectUtil;
import org.apache.hertzbeat.common.constants.CommonConstants;
import org.apache.hertzbeat.common.entity.job.Configmap;
import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.job.Metrics;
import org.apache.hertzbeat.common.timer.Timeout;
import org.apache.hertzbeat.common.timer.TimerTask;
import org.apache.hertzbeat.common.util.AesUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Timer Task implementation
 */
@Slf4j
public class WheelTimerTask implements TimerTask {

    private final Job job;
    private final MetricsTaskDispatch metricsTaskDispatch;
    private static final Gson GSON = new Gson();

    public WheelTimerTask(Job job, MetricsTaskDispatch metricsTaskDispatch) {
        this.metricsTaskDispatch = metricsTaskDispatch;
        this.job = job;
        initJobMetrics(job);
    }

    /**
     * Initialize job fill information
     * @param job job
     */
    private void initJobMetrics(Job job) {
        List<Configmap> config = job.getConfigmap();
        if (config == null) return;
        
        Map<String, Configmap> configmap = config.stream()
                .peek(item -> {
                    // decode password
                    if (item.getType() == CommonConstants.PARAM_TYPE_PASSWORD && item.getValue() != null) {
                        String decodeValue = AesUtil.aesDecode(String.valueOf(item.getValue()));
                        if (decodeValue == null) {
                            log.error("Aes Decode value {} error.", item.getValue());
                        }
                        item.setValue(decodeValue);
                    } else if (item.getValue() != null && item.getValue() instanceof String value) {
                        item.setValue(value.trim());
                    }
                })
                .collect(Collectors.toMap(Configmap::getKey, item -> item, (key1, key2) -> key1));
        List<Metrics> metrics = job.getMetrics();
        if (metrics == null) return;
        
        List<Metrics> metricsTmp = new ArrayList<>(metrics.size());
        for (Metrics metric : metrics) {
            JsonElement jsonElement = GSON.toJsonTree(metric);
            CollectUtil.replaceSmilingPlaceholder(jsonElement, configmap);
            metric = GSON.fromJson(jsonElement, Metrics.class);
            if (DispatchConstants.PROTOCOL_PUSH.equals(job.getApp())) {
                CollectUtil.replaceFieldsForPushStyleMonitor(metric, configmap);
            }
            metricsTmp.add(metric);
        }
        job.setMetrics(metricsTmp);
        job.initIntervals();
    }


    @Override
    public void run(Timeout timeout) throws Exception {
        job.setDispatchTime(System.currentTimeMillis());
        metricsTaskDispatch.dispatchMetricsTask(timeout);
    }

    public Job getJob() {
        return job;
    }
}
