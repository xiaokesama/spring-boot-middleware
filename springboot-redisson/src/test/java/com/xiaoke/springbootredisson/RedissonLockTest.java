package com.xiaoke.springbootredisson;

import com.xiaoke.springbootredisson.service.DistributedLockService;
import com.xiaoke.springbootredisson.service.RateLimitAndBloomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要本机 Redis（默认 localhost:6379）。
 */
@SpringBootTest
class RedissonLockTest {

    @Autowired
    private DistributedLockService lockService;

    @Autowired
    private RateLimitAndBloomService rateLimitAndBloomService;

    @Test
    @DisplayName("分布式锁：并发扣减 100 次，库存不会被扣成负数")
    void lockPreventsOversell() {
        String sku = "lock-test-sku";
        lockService.initStock(sku, 10);

        // 20 线程 × 5 次，共 100 次扣减，只有 10 次能成功
        List<Long> snapshot = lockService.concurrentDeduct(sku, 20, 5, true);

        assertEquals(0L, snapshot.get(0), "加锁后库存应正好扣到 0，实际 " + snapshot.get(0));
    }

    @Test
    @DisplayName("反例：不加锁时同样的并发会把库存扣穿")
    void withoutLockOversellHappens() {
        String sku = "unsafe-test-sku";
        lockService.initStock(sku, 10);

        List<Long> snapshot = lockService.concurrentDeduct(sku, 20, 5, false);

        assertTrue(snapshot.get(0) <= 0,
                "无锁并发下库存应被扣到 0 或负数（超卖），实际 " + snapshot.get(0));
    }

    @Test
    @DisplayName("看门狗：持有时间超过 watchdog(5s) 后锁仍然有效")
    void watchdogKeepsLockAlive() throws InterruptedException {
        var result = lockService.observeWatchdog("watchdog-test", 7_000);

        assertTrue(result.acquired());
        assertTrue(result.lockedAt1s(), "1 秒时锁应存在");
        assertTrue(result.lockedAtEnd(), "7 秒后（已超过 5s 初始有效期）锁应被看门狗续期");
    }

    @Test
    @DisplayName("可重入：同一线程重复加锁，重入计数为 2")
    void reentrant() {
        assertEquals(2, lockService.reentrant("reentrant-test"));
    }

    @Test
    @DisplayName("MultiLock：两把锁全部拿到才返回成功")
    void multiLock() {
        String result = lockService.multiLock("multi-test");
        assertTrue(result.contains("成功"));
    }

    @Test
    @DisplayName("限流器：1 秒内只放行 3 次")
    void rateLimiter() {
        var result = rateLimitAndBloomService.tryAcquire("order-api-" + System.nanoTime(), 3, 1, 8);

        long accepted = result.accepted().stream().filter(Boolean::booleanValue).count();
        assertEquals(3, accepted, "规则是 3 次/秒，8 次尝试应只放行 3 次");
    }

    @Test
    @DisplayName("布隆过滤器：已加入的元素判定存在，未加入的判定不存在")
    void bloomFilter() {
        var stats = rateLimitAndBloomService.bloomDemo(
                "sku-" + System.nanoTime(),
                List.of("sku-1", "sku-2", "sku-3"),
                List.of("sku-1", "sku-9999"));

        assertTrue(stats.probes().get(0).maybeExists(), "sku-1 已加入，应判定存在");
        assertFalse(stats.probes().get(1).maybeExists(), "sku-9999 未加入，应判定不存在");
    }
}
