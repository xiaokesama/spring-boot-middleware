package com.xiaoke.springbootsharding.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.broadcast.config.BroadcastRuleConfiguration;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableReferenceRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.keygen.KeyGenerateStrategyConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 用编程式 API 组装 ShardingSphere，而不是 YAML。
 *
 * <p>原因：5.5 的 YAML 驱动方式在不同小版本上对 {@code rules}/{@code mode} 的解析并不稳定，
 * 出问题时只能看到一句模糊的 "Table or view does not exist"。编程式配置的每一步都是显式的，
 * 而且可以把「建物理库 → 建物理表 → 建分片数据源」放在同一个方法里，顺序天然正确。
 *
 * <p><b>关于 H2 的大小写</b>：这里 DDL 用了带引号的小写标识符（{@code "t_order_0"}、{@code "order_id"}）。
 * 原因是 ShardingSphere 会用元数据里的列名和 inline 表达式里的变量做严格比对：
 * H2 默认把未加引号的标识符转成大写，元数据就成了 {@code ORDER_ID}，
 * 与表达式里的 {@code order_id} 不匹配，直接报
 * "Inline sharding algorithms expression ... and sharding column ... do not match"。
 *
 * <p>拓扑：
 * <pre>
 *   逻辑表 t_order
 *   ├── ds_0.t_order_0 / ds_0.t_order_1   （user_id % 2 = 0）
 *   └── ds_1.t_order_0 / ds_1.t_order_1   （user_id % 2 = 1）
 *   分表：order_id % 2
 *   广播表：t_dict（每个库一份）
 *   绑定表：t_order + t_order_item（同分片键，JOIN 不跨库）
 * </pre>
 */
@Slf4j
@Configuration
public class ShardingDataSourceConfig {

    private static final String[] SHARD_SUFFIXES = {"0", "1"};

    private static final String DDL_ORDER = """
            CREATE TABLE IF NOT EXISTS %s (
                "order_id" BIGINT PRIMARY KEY,
                "user_id"  BIGINT NOT NULL,
                "status"   VARCHAR(32),
                "amount"   DECIMAL(12, 2)
            )
            """;

    private static final String DDL_ORDER_ITEM = """
            CREATE TABLE IF NOT EXISTS %s (
                "order_item_id" BIGINT PRIMARY KEY,
                "order_id"      BIGINT NOT NULL,
                "user_id"       BIGINT NOT NULL,
                "product_name"  VARCHAR(64),
                "quantity"      INT
            )
            """;

    private static final String DDL_DICT = """
            CREATE TABLE IF NOT EXISTS "t_dict" (
                "dict_type"  VARCHAR(32) PRIMARY KEY,
                "dict_value" VARCHAR(64)
            )
            """;

    @Bean(name = "ds0", destroyMethod = "close")
    public HikariDataSource ds0() {
        return createPhysicalDataSource("jdbc:h2:mem:ds0;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "ds_0");
    }

    @Bean(name = "ds1", destroyMethod = "close")
    public HikariDataSource ds1() {
        return createPhysicalDataSource("jdbc:h2:mem:ds1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "ds_1");
    }

    @Bean
    @Primary
    public DataSource shardingDataSource(@Qualifier("ds0") HikariDataSource ds0,
                                         @Qualifier("ds1") HikariDataSource ds1) throws SQLException {
        // 1. 先建物理表（用的就是下面这两个连接池，库身份绝对不会错）
        initPhysicalSchema(ds0, "ds_0");
        initPhysicalSchema(ds1, "ds_1");

        // 2. 再建分片数据源
        Map<String, DataSource> dataSources = Map.of("ds_0", ds0, "ds_1", ds1);

        ShardingRuleConfiguration shardingRule = new ShardingRuleConfiguration();
        shardingRule.getTables().add(orderTableRule());
        shardingRule.getTables().add(orderItemTableRule());
        shardingRule.getBindingTableGroups().add(
                new ShardingTableReferenceRuleConfiguration("t_order_item_binding", "t_order,t_order_item"));
        shardingRule.setDefaultDatabaseShardingStrategy(
                new StandardShardingStrategyConfiguration("user_id", "database_inline"));

        shardingRule.getShardingAlgorithms().put("database_inline", inlineAlgorithm("ds_${user_id % 2}"));
        shardingRule.getShardingAlgorithms().put("t_order_inline", inlineAlgorithm("t_order_${order_id % 2}"));
        shardingRule.getShardingAlgorithms().put("t_order_item_inline",
                inlineAlgorithm("t_order_item_${order_id % 2}"));

        Properties snowflakeProps = new Properties();
        snowflakeProps.setProperty("worker-id", "1");
        shardingRule.getKeyGenerators().put("snowflake",
                new AlgorithmConfiguration("SNOWFLAKE", snowflakeProps));

        List<RuleConfiguration> rules = List.of(
                shardingRule,
                // 广播表：每个库都存一份全量数据
                new BroadcastRuleConfiguration(List.of("t_dict")));

        Properties props = new Properties();
        props.setProperty("sql-show", "true");

        DataSource dataSource = ShardingSphereDataSourceFactory.createDataSource(dataSources, rules, props);
        log.info("ShardingSphere 分片数据源创建完成：2 库 × 2 表 + 1 广播表");
        return dataSource;
    }

    private ShardingTableRuleConfiguration orderTableRule() {
        ShardingTableRuleConfiguration table = new ShardingTableRuleConfiguration(
                "t_order", "ds_${0..1}.t_order_${0..1}");
        table.setTableShardingStrategy(
                new StandardShardingStrategyConfiguration("order_id", "t_order_inline"));
        table.setKeyGenerateStrategy(new KeyGenerateStrategyConfiguration("order_id", "snowflake"));
        return table;
    }

    private ShardingTableRuleConfiguration orderItemTableRule() {
        ShardingTableRuleConfiguration table = new ShardingTableRuleConfiguration(
                "t_order_item", "ds_${0..1}.t_order_item_${0..1}");
        table.setTableShardingStrategy(
                new StandardShardingStrategyConfiguration("order_id", "t_order_item_inline"));
        table.setKeyGenerateStrategy(new KeyGenerateStrategyConfiguration("order_item_id", "snowflake"));
        return table;
    }

    private AlgorithmConfiguration inlineAlgorithm(String expression) {
        Properties props = new Properties();
        props.setProperty("algorithm-expression", expression);
        return new AlgorithmConfiguration("INLINE", props);
    }

    private HikariDataSource createPhysicalDataSource(String jdbcUrl, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername("sa");
        config.setPassword("");
        config.setDriverClassName("org.h2.Driver");
        config.setPoolName(poolName);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        return new HikariDataSource(config);
    }

    private void initPhysicalSchema(DataSource dataSource, String name) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String suffix : SHARD_SUFFIXES) {
                statement.execute(DDL_ORDER.formatted("\"t_order_" + suffix + "\""));
                statement.execute(DDL_ORDER_ITEM.formatted("\"t_order_item_" + suffix + "\""));
            }
            statement.execute(DDL_DICT);
            log.info("物理库 {} 建表完成", name);
        } catch (Exception e) {
            throw new IllegalStateException("初始化物理库失败: " + name, e);
        }
    }
}
