# springboot-redis

Redis 数据结构练习。端口 **8080**，依赖本机 Redis `6379`。

## 场景

`src/test/java/.../TestRedis.java` 里覆盖了 Redis 的全部常用结构，每个用例都自带数据准备：

| 结构 | 命令族 | 典型用途 |
|---|---|---|
| String | `SET/GET` | 会话、计数器 |
| Hash | `HSET/HGET` | 存对象的多个字段 |
| List | `LPUSH/RPUSH/LPOP` | 双端队列、简单消息队列 |
| Set | `SADD/SMEMBERS` | 去重、共同好友 |
| ZSet | `ZADD/ZRANGE` | 排行榜、延迟队列 |
| Bitmap | `SETBIT/GETBIT` | 签到、活跃用户（极致省内存） |
| HyperLogLog | `PFADD/PFCOUNT` | UV 基数统计（12KB 固定内存，1.2% 误差） |
| Geo | `GEOADD/GEODIST/GEORADIUS` | 附近的人、距离计算 |

HTTP 接口：

```powershell
curl.exe -X POST "http://localhost:8080/redis/set?key=k1&value=v1"
curl.exe "http://localhost:8080/redis/get?key=k1"
```

## 关键点

1. **`spring.data.redis.*` 是 Boot 3 的写法**，Boot 2 时代是 `spring.redis.*`。
2. **classpath 上同时有 Lettuce 和 Jedis 时必须显式指定**，否则默认 Lettuce：
   `spring.data.redis.client-type=jedis`。
3. **不要自己 `new JedisConnectionFactory()`**：会和自动配置产生两个同类型 Bean，
   而且自定义的 `redis.host` 属性根本不会被读取（这是本项目修掉的一个真实坑）。
4. **序列化方式决定存进去的字节**：本项目 key/value 都用 `StringRedisSerializer`，
   redis-cli 里可读、Bitmap 也能用；存对象则要换 `GenericJackson2JsonRedisSerializer`。
5. **`RedisTemplate<String,Object>` 与 `RedisTemplate<String,String>` 不通用**：
   按泛型注入，类型不匹配会启动失败。

## 测试

```powershell
mvn -B test -pl springboot-redis
```

18 个用例，需要 Redis 在 `localhost:6379` 运行。
