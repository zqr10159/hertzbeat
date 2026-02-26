package org.apache.hertzbeat.collector.core.collect.database;

import lombok.extern.slf4j.Slf4j;

/**
 * load the jdbc driver first to avoid spi concurrent deadlock
 */
@Slf4j
public class JdbcSpiLoader {

    public static void load() {
        log.info("start load jdbc drivers");
        try {
            Class.forName("org.postgresql.Driver");
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            Class.forName("dm.jdbc.driver.DmDriver");
            Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        } catch (Exception e) {
            log.error("load jdbc error: {}", e.getMessage());
        }
        log.info("end load jdbc drivers");
    }
}
