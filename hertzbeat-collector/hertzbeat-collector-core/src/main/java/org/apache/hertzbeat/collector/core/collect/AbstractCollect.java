package org.apache.hertzbeat.collector.core.collect;

import org.apache.hertzbeat.common.entity.job.Metrics;
import org.apache.hertzbeat.common.entity.message.CollectRep;

/**
 * Specific metrics collection implementation abstract class
 */
public abstract class AbstractCollect {

    /**
     * Pre-check metrics
     * @param metrics metric configuration
     * @throws IllegalArgumentException when validation failed
     */
    public abstract void preCheck(Metrics metrics) throws IllegalArgumentException;


    /**
     * Real acquisition implementation interface
     *
     * @param builder response builder
     * @param metrics metric configuration
     */
    public abstract void collect(CollectRep.MetricsData.Builder builder, Metrics metrics);

    /**
     * the protocol this collect instance support
     * @return protocol str
     */
    public abstract String supportProtocol();
}
