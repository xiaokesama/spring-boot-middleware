package com.xiaoke.springbootcache.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存三大问题的可运行演示：穿透、击穿、雪崩。
 *
 * <p>三者一句话区分：
 * <ul>
 *   <li><b>穿透</b>：查的是<b>根本不存在</b>的数据，缓存永远不命中，请求全打到数据库</li>
 *   <li><b>击穿</b>：某个<b>热点 key</b> 过期的瞬间，大量并发同时回源</li>
 *   <li><b>雪崩</b>：<b>大批 key 同时过期</b>（常见于统一 TTL），数据库被打满</li>
 * </ul>
 */
@Slf4j
@Service
public class CacheProblemService {

    /** 用于把「查不到」也缓存起来；用字符串哨兵而不是 null，因为 Spring Cache 不存 null */
    public static final String NULL_SENTINEL = "__NULL__";

    private final CacheManager cacheManager;

    @Value("${cache.slow-query-millis:300}")
    private long slowQueryMillis;

    public CacheProblemService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    // ------------------------------------------------------------------
    // 场景 1：缓存穿透
    // ------------------------------------------------------------------

    /**
     * @param cacheNull true = 把「不存在」也缓存起来（推荐做法）
     *                  false = 不做空值缓存，每次请求都会落到数据库
     */
    public PenetrationResult penetration(long id, int times, boolean cacheNull) {
        Cache cache = cacheManager.getCache("penetration");
        if (cache == null) {
            throw new IllegalStateException("缓存区 penetration 未初始化");
        }
        cache.clear();
        AtomicInteger dbHits = new AtomicInteger();

        for (int i = 0; i < times; i++) {
            queryUser(cache, id, cacheNull, dbHits);
        }
        int hits = dbHits.get();
        return new PenetrationResult(id, times, hits, cacheNull,
                cacheNull ? "第二次起命中空值缓存" : "每次都穿透到数据库");
    }

    private UserService.User queryUser(Cache cache, long id, boolean cacheNull, AtomicInteger dbHits) {
        Cache.ValueWrapper wrapper = cache.get(id);
        if (wrapper != null) {
            Object cached = wrapper.get();
            if (NULL_SENTINEL.equals(cached)) {
                return null;
            }
            return (UserService.User) cached;
        }

        dbHits.incrementAndGet();
        sleep();
        UserService.User user = id <= 0 ? null : new UserService.User(id, "user-" + id);

        if (user != null) {
            cache.put(id, user);
        } else if (cacheNull) {
            cache.put(id, NULL_SENTINEL);
        }
        return user;
    }

    // ------------------------------------------------------------------
    // 场景 2：缓存击穿
    // ------------------------------------------------------------------

    /**
     * 热点 key 过期后，{@code threads} 个并发同时回源。
     *
     * @param safe true  = 用 Caffeine 的原子 load（{@code cache.get(key, loader)}），只有一个线程回源
     *             false = 先 get 再 put 的「检查-写入」写法，并发下会重复回源
     */
    public BreakdownResult breakdown(long id, int threads, boolean safe) {
        Cache cache = cacheManager.getCache("breakdown");
        if (cache == null) {
            throw new IllegalStateException("缓存区 breakdown 未初始化");
        }
        cache.clear();

        AtomicInteger dbHits = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (safe) {
                        // Caffeine 内部对同一个 key 的 load 是原子的（ConcurrentHashMap#computeIfAbsent）
                        cache.get(id, () -> loadUser(id, dbHits));
                    } else {
                        if (cache.get(id) == null) {
                            UserService.User user = loadUser(id, dbHits);
                            cache.put(id, user);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        int hits = dbHits.get();
        return new BreakdownResult(id, threads, hits, safe,
                safe ? "原子 load：只回源 1 次" : "检查-写入：回源 " + hits + " 次");
    }

    private UserService.User loadUser(long id, AtomicInteger dbHits) {
        dbHits.incrementAndGet();
        sleep();
        return new UserService.User(id, "hot-" + id);
    }

    // ------------------------------------------------------------------
    // 场景 3：缓存雪崩
    // ------------------------------------------------------------------

    /**
     * 统一 TTL 会让一批 key 在同一秒失效，加随机抖动可以打散失效时间。
     */
    public AvalancheResult avalanche(int count, long baseSeconds, long jitterSeconds) {
        List<Long> ttls = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long jitter = jitterSeconds <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
            ttls.add(baseSeconds + jitter);
        }
        long min = ttls.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = ttls.stream().mapToLong(Long::longValue).max().orElse(0);
        long distinct = ttls.stream().distinct().count();
        return new AvalancheResult(baseSeconds, jitterSeconds, min, max, (int) distinct, count,
                "抖动后 TTL 落在 [" + min + ", " + max + "] 秒，共 " + distinct + " 种取值");
    }

    private void sleep() {
        try {
            Thread.sleep(slowQueryMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record PenetrationResult(long queryId, int times, int dbHits, boolean cacheNull, String note) {
    }

    public record BreakdownResult(long hotKey, int threads, int dbHits, boolean safe, String note) {
    }

    public record AvalancheResult(long baseSeconds, long jitterSeconds, long minTtl, long maxTtl,
                                  int distinctTtl, int count, String note) {
    }
}
