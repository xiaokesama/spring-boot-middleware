package com.xiaoke.springbootredisson.controller;

import com.xiaoke.springbootredisson.service.DistributedLockService;
import com.xiaoke.springbootredisson.service.RateLimitAndBloomService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/redisson")
public class DemoController {

    private final DistributedLockService lockService;
    private final RateLimitAndBloomService rateLimitAndBloomService;

    public DemoController(DistributedLockService lockService,
                          RateLimitAndBloomService rateLimitAndBloomService) {
        this.lockService = lockService;
        this.rateLimitAndBloomService = rateLimitAndBloomService;
    }

    // ---------- 分布式锁 ----------

    @PostMapping("/stock/init")
    public long initStock(@RequestParam String sku, @RequestParam long stock) {
        return lockService.initStock(sku, stock);
    }

    @GetMapping("/stock")
    public long stock(@RequestParam String sku) {
        return lockService.currentStock(sku);
    }

    @PostMapping("/stock/deduct")
    public DistributedLockService.DeductResult deduct(@RequestParam String sku,
                                                      @RequestParam(defaultValue = "1") int quantity,
                                                      @RequestParam(defaultValue = "50") long workMillis) {
        return lockService.deduct(sku, quantity, workMillis);
    }

    @GetMapping("/watchdog")
    public DistributedLockService.WatchdogResult watchdog(@RequestParam(defaultValue = "default") String name,
                                                          @RequestParam(defaultValue = "8000") long holdMillis)
            throws InterruptedException {
        return lockService.observeWatchdog(name, holdMillis);
    }

    @GetMapping("/reentrant")
    public int reentrant(@RequestParam(defaultValue = "default") String name) {
        return lockService.reentrant(name);
    }

    @GetMapping("/multilock")
    public String multiLock(@RequestParam(defaultValue = "1") String bizId) {
        return lockService.multiLock(bizId);
    }

    /**
     * 对比实验：useLock=false 时并发扣减会出现超卖（库存变负）。
     */
    @PostMapping("/stock/concurrent")
    public List<Long> concurrentDeduct(@RequestParam String sku,
                                       @RequestParam(defaultValue = "20") int threads,
                                       @RequestParam(defaultValue = "10") int perThread,
                                       @RequestParam(defaultValue = "true") boolean useLock) {
        return lockService.concurrentDeduct(sku, threads, perThread, useLock);
    }

    // ---------- 限流 ----------

    @PostMapping("/limiter")
    public RateLimitAndBloomService.LimitResult limiter(@RequestParam(defaultValue = "order") String name,
                                                        @RequestParam(defaultValue = "5") long permits,
                                                        @RequestParam(defaultValue = "1") long seconds,
                                                        @RequestParam(defaultValue = "8") int attempts) {
        return rateLimitAndBloomService.tryAcquire(name, permits, seconds, attempts);
    }

    // ---------- 布隆过滤器 ----------

    @PostMapping("/bloom")
    public RateLimitAndBloomService.BloomStats bloom(@RequestParam(defaultValue = "sku") String name,
                                                     @RequestParam List<String> exists,
                                                     @RequestParam List<String> probes) {
        return rateLimitAndBloomService.bloomDemo(name, exists, probes);
    }
}
