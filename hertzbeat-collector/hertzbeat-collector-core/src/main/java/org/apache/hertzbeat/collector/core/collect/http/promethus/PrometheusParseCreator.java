package org.apache.hertzbeat.collector.core.collect.http.promethus;

import lombok.extern.slf4j.Slf4j;

/**
 * prometheus parse creater
 */
@Slf4j
public class PrometheusParseCreator {
    private static final AbstractPrometheusParse PROMETHEUS_PARSE = new PrometheusVectorParser();

    static {
        create();
    }

    private static void create() {
        PROMETHEUS_PARSE.setInstance(new PrometheusMatrixParser().setInstance(new PrometheusLastParser()));
    }

    public static AbstractPrometheusParse getPrometheusParse(){
        return PROMETHEUS_PARSE;
    }
}
