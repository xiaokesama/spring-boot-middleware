package com.xiaoke.springbootcache.controller;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.xiaoke.springbootcache.service.CacheProblemService;
import com.xiaoke.springbootcache.service.UserService;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/cache")
public class DemoController {

    private final UserService userService;
    private final CacheProblemService cacheProblemService;
    private final CacheManager cacheManager;

    public DemoController(UserService userService,
                          CacheProblemService cacheProblemService,
                          CacheManager cacheManager) {
        this.userService = userService;
        this.cacheProblemService = cacheProblemService;
        this.cacheManager = cacheManager;
    }

    // ---------- 声明式缓存 ----------

    @GetMapping("/user/{id}")
    public UserService.User findUser(@PathVariable long id) {
        return userService.findById(id);
    }

    @PostMapping("/user")
    public UserService.User updateUser(@RequestBody UserService.User user) {
        return userService.update(user);
    }

    @DeleteMapping("/user/{id}")
    public String deleteUser(@PathVariable long id) {
        userService.delete(id);
        return "deleted";
    }

    @GetMapping("/user/db-hits")
    public Map<String, Object> dbHits() {
        return Map.of("dbHits", userService.dbHitCount());
    }

    @PostMapping("/user/reset")
    public String reset() {
        userService.resetCounter();
        cacheManager.getCache("user").clear();
        return "reset";
    }

    // ---------- 三大问题 ----------

    @PostMapping("/problem/penetration")
    public CacheProblemService.PenetrationResult penetration(@RequestParam(defaultValue = "-1") long id,
                                                             @RequestParam(defaultValue = "10") int times,
                                                             @RequestParam(defaultValue = "true") boolean cacheNull) {
        return cacheProblemService.penetration(id, times, cacheNull);
    }

    @PostMapping("/problem/breakdown")
    public CacheProblemService.BreakdownResult breakdown(@RequestParam(defaultValue = "1") long id,
                                                         @RequestParam(defaultValue = "10") int threads,
                                                         @RequestParam(defaultValue = "true") boolean safe) {
        return cacheProblemService.breakdown(id, threads, safe);
    }

    @GetMapping("/problem/avalanche")
    public CacheProblemService.AvalancheResult avalanche(@RequestParam(defaultValue = "20") int count,
                                                         @RequestParam(defaultValue = "60") long baseSeconds,
                                                         @RequestParam(defaultValue = "30") long jitterSeconds) {
        return cacheProblemService.avalanche(count, baseSeconds, jitterSeconds);
    }

    // ---------- 命中率 ----------

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : cacheManager.getCacheNames()) {
            org.springframework.cache.Cache cache = cacheManager.getCache(name);
            if (cache instanceof CaffeineCache caffeineCache) {
                CacheStats s = caffeineCache.getNativeCache().stats();
                result.put(name, Map.of(
                        "size", caffeineCache.getNativeCache().estimatedSize(),
                        "hitCount", s.hitCount(),
                        "missCount", s.missCount(),
                        "hitRate", String.format("%.2f%%", s.hitRate() * 100),
                        "evictionCount", s.evictionCount()
                ));
            }
        }
        return result;
    }
}
