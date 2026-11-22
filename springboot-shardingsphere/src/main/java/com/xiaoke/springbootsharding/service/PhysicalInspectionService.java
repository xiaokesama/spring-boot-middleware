package com.xiaoke.springbootsharding.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 绕过 ShardingSphere 直连两个物理库，用来验证「数据到底落到哪张表」。
 *
 * <p>这里注入的是 ShardingDataSourceConfig 里创建的那两个 HikariDataSource 本身，
 * 而不是另开 {@code DriverManager} 连接 —— 同一个连接池才能保证看到的是同一份数据。
 * 这是排查分片问题最常用的手段：逻辑上一条数据，物理上到底在哪。
 */
@Service
public class PhysicalInspectionService {

    private static final List<String> TABLES =
            List.of("t_order_0", "t_order_1", "t_order_item_0", "t_order_item_1", "t_dict");

    private final DataSource ds0;
    private final DataSource ds1;

    public PhysicalInspectionService(@Qualifier("ds0") DataSource ds0,
                                     @Qualifier("ds1") DataSource ds1) {
        this.ds0 = ds0;
        this.ds1 = ds1;
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ds_0", countByTable(ds0));
        result.put("ds_1", countByTable(ds1));
        return result;
    }

    public void truncateAll() {
        truncate(ds0);
        truncate(ds1);
    }

    private Map<String, Long> countByTable(DataSource dataSource) {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    counts.put(table, rs.getLong(1));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("统计物理表失败", e);
        }
        return counts;
    }

    private void truncate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                statement.execute("DELETE FROM " + table);
            }
        } catch (Exception e) {
            throw new IllegalStateException("清空物理表失败", e);
        }
    }
}
