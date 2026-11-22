package com.xiaoke.springbootclickhouse;

import com.xiaoke.springbootclickhouse.repository.EventRepository;
import com.xiaoke.springbootclickhouse.service.EventSeeder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要本机 ClickHouse（WSL2 里 apt 安装，8123 端口）。
 * 连不上时整类跳过，不影响 CI。
 */
@SpringBootTest
class ClickHouseIntegrationTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventSeeder eventSeeder;

    @BeforeEach
    void requireClickHouse() {
        Assumptions.assumeTrue(reachable(), "ClickHouse 未启动（localhost:8123），跳过集成测试");
        eventRepository.truncate();
    }

    @Test
    @DisplayName("批量写入 5000 条并统计总量")
    void batchInsert() {
        eventSeeder.seed(5_000);
        assertEquals(5_000, eventRepository.count());
    }

    @Test
    @DisplayName("按类型聚合：4 种事件都有数据且 UV 不为 0")
    void aggregateByType() {
        eventSeeder.seed(2_000);

        List<Map<String, Object>> rows = eventRepository.statsByType();
        assertEquals(4, rows.size(), "应该正好 4 种事件类型");
        rows.forEach(row -> {
            assertTrue(((Number) row.get("cnt")).longValue() > 0);
            assertTrue(((Number) row.get("uv")).longValue() > 0);
        });
    }

    @Test
    @DisplayName("物化视图：预聚合结果与明细表总量一致")
    void materializedViewMatchesDetail() {
        eventSeeder.seed(1_000);

        long detailTotal = eventRepository.statsByType().stream()
                .mapToLong(row -> ((Number) row.get("cnt")).longValue())
                .sum();
        long mvTotal = eventRepository.materializedView().stream()
                .mapToLong(row -> ((Number) row.get("cnt")).longValue())
                .sum();

        assertEquals(detailTotal, mvTotal);
        assertFalse(eventRepository.dailyTrend().isEmpty());
    }

    private boolean reachable() {
        // 不能只测端口：ClickHouse 刚启动时端口已监听但查询会失败。
        // 这里真的发一条 SELECT 1，能通过才算可用。
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:clickhouse://127.0.0.1:8123/default?compress=0", "default", "");
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }
}
