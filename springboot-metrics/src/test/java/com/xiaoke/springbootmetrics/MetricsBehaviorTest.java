package com.xiaoke.springbootmetrics;

import com.xiaoke.springbootmetrics.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MetricsBehaviorTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("Counter / DistributionSummary：下单成功后计数与金额分布同步累加")
    void countersIncreaseOnSuccess() {
        double before = meterRegistry.get("lab.order.created").counter().count();

        orderService.create(100, false);
        orderService.create(200, false);

        assertEquals(before + 2, meterRegistry.get("lab.order.created").counter().count(), 0.001);
        assertTrue(meterRegistry.get("lab.order.amount").summary().count() >= 2);
    }

    @Test
    @DisplayName("@Timed：方法调用会写入计时器，失败调用也会计入")
    void timedAspectRecordsLatency() {
        double before = meterRegistry.get("lab.order.create.latency").timer().count();

        orderService.create(50, false);
        assertThrows(IllegalStateException.class, () -> orderService.create(50, true));

        // 两次调用都应被计时
        assertEquals(before + 2, meterRegistry.get("lab.order.create.latency").timer().count(), 0.001);
    }

    @Test
    @DisplayName("Counter：失败次数只增不减")
    void failedCounterCountsErrors() {
        double before = meterRegistry.get("lab.order.failed").counter().count();
        assertThrows(IllegalStateException.class, () -> orderService.create(10, true));
        assertEquals(before + 1, meterRegistry.get("lab.order.failed").counter().count(), 0.001);
    }

    @Test
    @DisplayName("Gauge：请求结束后 pending 回到 0")
    void pendingGaugeReturnsToZero() {
        orderService.create(10, false);
        assertEquals(0, orderService.pendingCount());
        assertEquals(0.0, meterRegistry.get("lab.order.pending").gauge().value(), 0.001);
    }
}
