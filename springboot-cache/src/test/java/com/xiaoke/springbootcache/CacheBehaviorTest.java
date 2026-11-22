package com.xiaoke.springbootcache;

import com.xiaoke.springbootcache.service.CacheProblemService;
import com.xiaoke.springbootcache.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class CacheBehaviorTest {

    @Autowired
    private UserService userService;

    @Autowired
    private CacheProblemService problemService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void reset() {
        userService.resetCounter();
        userService.clearAll();
    }

    @Test
    @DisplayName("声明式缓存：第二次查询不再回源，且拿到同一个对象")
    void declarativeCacheHits() {
        var first = userService.findById(100L);
        var second = userService.findById(100L);
        assertEquals(1, userService.dbHitCount());
        assertSame(first, second);
    }

    @Test
    @DisplayName("@CacheEvict：删除后再次查询会重新回源")
    void cacheEvictedOnDelete() {
        userService.findById(101L);
        userService.delete(101L);
        userService.findById(101L);
        assertEquals(2, userService.dbHitCount());
    }

    @Test
    @DisplayName("@CachePut：更新后缓存里的值同步变化，且不额外回源")
    void cachePutRefreshesValue() {
        userService.findById(102L);
        userService.update(new UserService.User(102L, "renamed"));
        var user = userService.findById(102L);
        assertEquals("renamed", user.name());
        assertEquals(1, userService.dbHitCount());
    }

    @Test
    @DisplayName("缓存穿透：缓存空值后只回源 1 次，否则每次都穿透")
    void nullCachingStopsPenetration() {
        var withNullCaching = problemService.penetration(-1L, 5, true);
        var withoutNullCaching = problemService.penetration(-1L, 5, false);

        assertEquals(1, withNullCaching.dbHits());
        assertEquals(5, withoutNullCaching.dbHits());
    }

    @Test
    @DisplayName("缓存击穿：Caffeine 原子 load 只回源 1 次")
    void atomicLoadPreventsBreakdown() {
        var result = problemService.breakdown(7L, 10, true);
        assertEquals(1, result.dbHits());
    }

    @Test
    @DisplayName("缓存击穿：先 get 再 put 在并发下会重复回源")
    void checkThenPutBreaksDown() {
        var result = problemService.breakdown(8L, 10, false);
        assertTrue(result.dbHits() > 1, "并发下应该出现多次回源，实际 " + result.dbHits());
    }

    @Test
    @DisplayName("缓存雪崩：TTL 抖动后失效时间被打散")
    void ttlJitterSpreadsExpiry() {
        var result = problemService.avalanche(100, 60, 30);
        assertEquals(60, result.minTtl());
        assertEquals(90, result.maxTtl());
        assertTrue(result.distinctTtl() > 1);
    }
}
