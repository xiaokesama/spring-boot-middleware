package com.xiaoke.springbootsharding;

import com.xiaoke.springbootsharding.repository.OrderRepository;
import com.xiaoke.springbootsharding.service.PhysicalInspectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ShardingRoutingTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PhysicalInspectionService inspectionService;

    @BeforeEach
    void clean() {
        inspectionService.truncateAll();
    }

    @Test
    @DisplayName("分布式主键：不传 order_id 也能得到唯一的雪花 ID")
    void snowflakeKeyGenerated() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            ids.add(orderRepository.insertOrder(2000 + i, BigDecimal.TEN));
        }
        assertEquals(10, ids.size(), "雪花算法生成的主键必须唯一");
        // 雪花 ID 通常远大于 0，且带时间戳量级
        assertTrue(ids.iterator().next() > 1_000_000L);
    }

    @Test
    @DisplayName("分库分表：逻辑表写入后，数据真的分散到 4 张物理表")
    void dataSpreadAcrossPhysicalTables() {
        for (int i = 0; i < 20; i++) {
            orderRepository.insertOrder(1000 + i, BigDecimal.valueOf(i));
        }

        Map<String, Object> stats = inspectionService.stats();
        long total = sum((Map<String, Long>) stats.get("ds_0"), "t_order_")
                + sum((Map<String, Long>) stats.get("ds_1"), "t_order_");
        assertEquals(20, total, "物理表里应正好有 20 条");

        // 20 条数据不可能只落在一张物理表上
        long nonEmptyTables = countNonEmpty((Map<String, Long>) stats.get("ds_0"))
                + countNonEmpty((Map<String, Long>) stats.get("ds_1"));
        assertTrue(nonEmptyTables > 1, "分片后应分散到多张物理表，实际 " + nonEmptyTables);
    }

    @Test
    @DisplayName("路由查询：带上分片键才能定位到具体库表")
    void routedQueryWorks() {
        long userId = 1001;
        long orderId = orderRepository.insertOrder(userId, BigDecimal.valueOf(88));

        List<Map<String, Object>> rows = orderRepository.findByOrderId(orderId, userId);
        assertEquals(1, rows.size());
    }

    @Test
    @DisplayName("绑定表：t_order 与 t_order_item 在同一库内 JOIN")
    void bindingTableJoin() {
        long userId = 1002;
        long orderId = orderRepository.insertOrder(userId, BigDecimal.valueOf(66));
        orderRepository.insertItem(orderId, userId, "keyboard", 2);

        List<Map<String, Object>> rows = orderRepository.joinOrderItems(orderId, userId);
        assertEquals(1, rows.size());
        assertEquals("keyboard", rows.get(0).get("product_name"));
    }

    @Test
    @DisplayName("广播表：写一次，两个物理库都能查到")
    void broadcastTableWrittenToAllShards() {
        orderRepository.insertDict("status", "CREATED");

        Map<String, Object> stats = inspectionService.stats();
        long ds0 = ((Map<String, Long>) stats.get("ds_0")).get("t_dict");
        long ds1 = ((Map<String, Long>) stats.get("ds_1")).get("t_dict");

        assertEquals(1, ds0);
        assertEquals(1, ds1);
        assertEquals(1, orderRepository.findDict("status").size());
    }

    private long sum(Map<String, Long> counts, String prefix) {
        return counts.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private long countNonEmpty(Map<String, Long> counts) {
        return counts.values().stream().filter(v -> v > 0).count();
    }
}
