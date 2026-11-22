package com.xiaoke.springbootclickhouse.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 建库建表。
 *
 * <p>ClickHouse 的建表核心是<b>表引擎</b>，这里用最常用的 MergeTree：
 * <ul>
 *   <li>{@code ORDER BY} 决定数据在磁盘上的排序（也就是主键索引），必须选「查询里最常用来过滤的列」</li>
 *   <li>MergeTree 的排序键不要求唯一，重复写入不会报错 —— 这点和 MySQL 主键完全不同</li>
 *   <li>不适合单条 UPDATE/DELETE（是批量重写 part），所以它是 OLAP 而不是 OLTP</li>
 * </ul>
 *
 * <p>物化视图 {@code mv_daily_event} 用 SummingMergeTree 做预聚合：
 * 写入明细表时自动增量聚合，查询时只需要 sum 一下已经聚合好的结果。
 *
 * <p>用 {@link InitializingBean} 而不是 ApplicationRunner：后者在 @SpringBootTest 里不保证执行。
 * 同时这里容忍建表失败——ClickHouse 没启动时不应该拖垮整个上下文（集成测试会自行跳过）。
 */
@Slf4j
@Component
public class ClickHouseSchemaInitializer implements InitializingBean {

    @Value("${clickhouse.database:lab}")
    private String database;

    @Value("${clickhouse.table:user_events}")
    private String table;

    private final JdbcTemplate jdbcTemplate;

    public ClickHouseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            createSchema();
        } catch (Exception e) {
            log.warn("ClickHouse 初始化失败（服务可能未启动），模块仍可启动：{}", e.getMessage());
        }
    }

    private void createSchema() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + database);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s.%s (
                    event_date Date,
                    event_time DateTime,
                    user_id    UInt64,
                    event_type LowCardinality(String),
                    amount     Decimal(10, 2)
                )
                ENGINE = MergeTree
                PARTITION BY toYYYYMM(event_date)
                ORDER BY (event_date, event_type, user_id)
                """.formatted(database, table));

        // 预聚合物化视图：明细写入时自动按 (日期, 事件类型) 累加
        jdbcTemplate.execute("""
                CREATE MATERIALIZED VIEW IF NOT EXISTS %1$s.mv_daily_event
                ENGINE = SummingMergeTree
                ORDER BY (event_date, event_type)
                AS SELECT
                       event_date,
                       event_type,
                       count()      AS cnt,
                       sum(amount)  AS total
                FROM %1$s.%2$s
                GROUP BY event_date, event_type
                """.formatted(database, table));

        log.info("ClickHouse 初始化完成：库={}，明细表={}，物化视图=mv_daily_event", database, table);
    }
}
