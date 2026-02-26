/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.collector.core.dispatch;

import org.apache.hertzbeat.common.timer.Timeout;
import org.apache.hertzbeat.common.entity.job.Metrics;

/**
 * Metrics collection task scheduler interface
 */
public interface MetricsTaskDispatch {

    /**
     * schedule task
     * @param timeout timeout
     */
    void dispatchMetricsTask(Timeout timeout);

    /**
     * dispatch collect data
     * @param timeout timeout
     * @param metrics metrics
     * @param metricsData metrics data
     */
    void dispatchCollectData(Timeout timeout, Metrics metrics, org.apache.hertzbeat.common.entity.message.CollectRep.MetricsData metricsData);


    /**
     * dispatch collect data list
     * @param timeout timeout
     * @param metrics metrics
     * @param metricsDataList metrics data list
     */
    void dispatchCollectData(Timeout timeout, Metrics metrics, java.util.List<org.apache.hertzbeat.common.entity.message.CollectRep.MetricsData> metricsDataList);
}

