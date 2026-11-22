package com.xiaoke.springbootredisson.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * RedissonClient 配置。
 *
 * <p>学习点：
 * <ul>
 *   <li>没有用 starter，而是手动 {@code Redisson.create(config)}，能一眼看到连接池、超时、编解码器这些关键参数。</li>
 *   <li>{@code lockWatchdogTimeout} 默认 30s。这里调成 5s 是为了方便观察「看门狗续期」：
 *       加锁时若不指定 leaseTime，Redisson 每 1/3 个 watchdog 周期（约 1.7s）续期一次。</li>
 *   <li>编解码器用 {@link JsonJacksonCodec}，redis-cli 里能看到可读的 JSON，调试友好（默认的二进制编码看不懂）。</li>
 * </ul>
 */
@Configuration
public class RedissonConfig {

    @Value("${redisson.address}")
    private String address;

    @Value("${redisson.password:}")
    private String password;

    @Value("${redisson.database:0}")
    private int database;

    @Value("${redisson.timeout:3000}")
    private int timeout;

    @Value("${redisson.pool-size:16}")
    private int poolSize;

    @Value("${redisson.min-idle-size:4}")
    private int minIdleSize;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setCodec(new JsonJacksonCodec());
        // 看门狗超时：默认 30s，这里调小便于演示自动续期
        config.setLockWatchdogTimeout(5_000);

        config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setTimeout(timeout)
                .setConnectionPoolSize(poolSize)
                .setConnectionMinimumIdleSize(minIdleSize)
                .setPassword(StringUtils.hasText(password) ? password : null);

        return Redisson.create(config);
    }
}
