package com.xiaoke.springbootredis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate 定制。
 *
 * <p>注意两个学习点：
 * <ol>
 *   <li><b>连接工厂不要自己 new</b>：引入 spring-boot-starter-data-redis 后，
 *       Boot 会依据 {@code spring.data.redis.*} 自动创建连接工厂；
 *       {@code spring.data.redis.client-type=jedis} 决定用 Jedis 而不是默认的 Lettuce。
 *       自己再 new 一个 {@code JedisConnectionFactory} 会与自动配置冲突（两个同类型 Bean），
 *       而且自定义的 {@code redis.host} 属性根本不会被读取。</li>
 *   <li><b>序列化方式决定存进去的字节</b>：这里 key / value 都用 {@link StringRedisSerializer}，
 *       好处是 redis-cli 里能直接读，Bitmap（setBit）等命令也能正常工作；
 *       代价是所有值都按字符串存，存对象需要换成 {@code GenericJackson2JsonRedisSerializer}。</li>
 * </ol>
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
