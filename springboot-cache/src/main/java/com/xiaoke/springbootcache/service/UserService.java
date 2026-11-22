package com.xiaoke.springbootcache.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 声明式缓存：{@code @Cacheable} / {@code @CachePut} / {@code @CacheEvict}。
 *
 * <p>注意 {@code @Cacheable} 的局限性：<b>返回 null 不会写缓存</b>，
 * 所以「查不存在的 ID」这种缓存穿透场景必须手动处理（见 {@link CacheProblemService}）。
 */
@Slf4j
@Service
public class UserService {

    private final AtomicInteger dbHits = new AtomicInteger();

    @Value("${cache.slow-query-millis:300}")
    private long slowQueryMillis;

    /** 第一次调用走「数据库」，之后命中缓存 */
    @Cacheable(cacheNames = "user", key = "#id")
    public User findById(long id) {
        int hits = dbHits.incrementAndGet();
        log.info("模拟查库 id={}，这是第 {} 次真实查询", id, hits);
        sleep();
        return new User(id, "user-" + id);
    }

    /** 更新数据并同步刷新缓存，保证读写一致 */
    @CachePut(cacheNames = "user", key = "#user.id")
    public User update(User user) {
        log.info("模拟更新 id={} 并刷新缓存", user.id());
        return user;
    }

    /** 删除数据时清除缓存，否则会读到脏数据 */
    @CacheEvict(cacheNames = "user", key = "#id")
    public void delete(long id) {
        log.info("模拟删除 id={} 并清除缓存", id);
    }

    /** 清空整个 user 缓存区 */
    @CacheEvict(cacheNames = "user", allEntries = true)
    public void clearAll() {
    }

    public int dbHitCount() {
        return dbHits.get();
    }

    public void resetCounter() {
        dbHits.set(0);
    }

    private void sleep() {
        try {
            Thread.sleep(slowQueryMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record User(long id, String name) {
    }
}
