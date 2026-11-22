package com.xiaoke.springbootcache.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caffeine 本地缓存配置。
 *
 * <p>Spring Cache 是一层抽象（{@code @Cacheable} / {@code CacheManager}），
 * 底层可以换 Caffeine、Redis、EhCache。这里用 Caffeine，它的淘汰策略是
 * W-TinyLFU，命中率通常明显优于 LRU。
 */
@Configuration
public class CacheConfig {

    @Value("${cache.caffeine.initial-capacity:100}")
    private int initialCapacity;

    @Value("${cache.caffeine.maximum-size:1000}")
    private long maximumSize;

    @Value("${cache.caffeine.expire-after-write-seconds:60}")
    private long expireAfterWriteSeconds;

    @Value("${cache.caffeine.record-stats:true}")
    private boolean recordStats;

    @Bean
    public CacheManager cacheManager() {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .initialCapacity(initialCapacity)
                .maximumSize(maximumSize)
                // 统一 TTL 是「缓存雪崩」的隐患之一，真实项目通常加随机抖动
                .expireAfterWrite(Duration.ofSeconds(expireAfterWriteSeconds));

        if (recordStats) {
            // 打开统计后才能读到 hitCount / missCount / evictionCount
            builder.recordStats();
        }

        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(builder);
        // 允许 @Cacheable 使用未预先声明的 cacheName
        manager.setAllowNullValues(false);
        return manager;
    }
}
