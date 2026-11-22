# springboot-cache

Spring Cache + Caffeine 本地缓存，以及缓存三大问题的**对照实验**。端口 **8086**，无外部依赖。

## 为什么用 Caffeine

Spring Cache 只是抽象层（`@Cacheable` / `CacheManager`），底层可换 Caffeine、Redis、EhCache。
Caffeine 的淘汰策略是 W-TinyLFU，扫描类场景下命中率通常明显优于 LRU。

## 四个场景

| 场景 | 接口 | 观察点 |
|---|---|---|
| 声明式缓存 | `GET /cache/user/1` 连续调两次 | 第二次不再回源，`/cache/user/db-hits` 仍是 1 |
| 更新/删除同步 | `POST /cache/user`、`DELETE /cache/user/1` | `@CachePut` 刷新缓存、`@CacheEvict` 清除缓存 |
| 缓存穿透 | `POST /cache/problem/penetration?id=-1&times=5&cacheNull=true` | 缓存空值后只回源 1 次；`cacheNull=false` 会穿透 5 次 |
| 缓存击穿 | `POST /cache/problem/breakdown?id=1&threads=10&safe=true` | 原子 load 只回源 1 次；`safe=false` 会回源多次 |
| 缓存雪崩 | `GET /cache/problem/avalanche?count=100&jitterSeconds=30` | TTL 抖动后失效时间被打散到 60~90s |
| 命中率 | `GET /cache/stats` | 各缓存区的 hitCount / missCount / hitRate / evictionCount |

## 运行

```powershell
mvn -B spring-boot:run -pl springboot-cache

# 走两次，观察缓存命中
curl.exe "http://localhost:8086/cache/user/1"
curl.exe "http://localhost:8086/cache/user/1"
curl.exe "http://localhost:8086/cache/user/db-hits"
```

## 关键点

1. **`@Cacheable` 返回 null 不会写缓存**。查不存在的 ID 永远不命中，
   必须手动缓存"空值哨兵"（本项目用 `"__NULL__"`），这是穿透治理的第一道防线。
2. **击穿的本质是"并发回源"**。Caffeine 的 `cache.get(key, loader)` 对同一个 key 是原子的
   （内部 `ConcurrentHashMap#computeIfAbsent`），所以能保证只回源一次；
   而"先 get 再 put"的检查-写入写法在并发下会重复回源 —— 两个接口都提供了对照。
3. **雪崩来自统一 TTL**。真实项目里给 TTL 加随机抖动（例如 30 分钟 ± 5 分钟），
   避免整批 key 在同一秒失效。
4. **Caffeine 是进程内缓存**，多实例部署时各存各的。要共享就得用 Redis，
   常见做法是「本地 Caffeine + 远程 Redis」的多级缓存，本地挡热点、远程保一致。
5. 打开 `recordStats()` 才能读到命中率；生产上这个指标应接入 Prometheus（见 `springboot-metrics`）。

## 测试

```powershell
mvn -B test -pl springboot-cache
```

7 个用例全部不依赖外部服务，可直接在 CI 里跑。
