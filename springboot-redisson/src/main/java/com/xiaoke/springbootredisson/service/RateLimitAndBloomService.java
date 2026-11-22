package com.xiaoke.springbootredisson.service;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 限流器与布隆过滤器。
 */
@Slf4j
@Service
public class RateLimitAndBloomService {

    private final RedissonClient redisson;

    public RateLimitAndBloomService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /**
     * 场景：令牌桶限流。
     *
     * <p>{@code trySetRate} 只在第一次生效（Redis 里已存在限流配置时返回 false），
     * 所以重复调用不会把已消耗的令牌重置掉。
     *
     * @param permits  时间窗口内允许的次数
     * @param seconds  窗口长度（秒）
     * @param attempts 本次尝试获取的次数，用于观察哪些被拒绝
     */
    public LimitResult tryAcquire(String name, long permits, long seconds, int attempts) {
        RRateLimiter limiter = redisson.getRateLimiter("lab:limiter:" + name);
        boolean initialized = limiter.trySetRate(RateType.OVERALL, permits, seconds, RateIntervalUnit.SECONDS);
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(limiter.tryAcquire());
        }
        return new LimitResult(initialized, results, permits + " 次 / " + seconds + " 秒");
    }

    /**
     * 场景：布隆过滤器挡「无效 ID」。
     *
     * <p>布隆过滤器只会误判「存在」（false positive），不会漏判「不存在」，
     * 因此非常适合放在缓存之前拦截缓存穿透。
     */
    public BloomStats bloomDemo(String filterName, List<String> exists, List<String> probes) {
        RBloomFilter<String> filter = redisson.getBloomFilter("lab:bloom:" + filterName);
        // 预计元素数 10 万，误判率 1%
        filter.tryInit(100_000L, 0.01);
        exists.forEach(filter::add);

        List<ProbeResult> results = new ArrayList<>();
        for (String probe : probes) {
            results.add(new ProbeResult(probe, filter.contains(probe)));
        }
        return new BloomStats(filter.getExpectedInsertions(), filter.getFalseProbability(), results);
    }

    public record LimitResult(boolean firstInit, List<Boolean> accepted, String rule) {
    }

    public record ProbeResult(String value, boolean maybeExists) {
    }

    public record BloomStats(long expectedInsertions, double falseProbability, List<ProbeResult> probes) {
    }
}
