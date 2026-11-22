# springboot-redisson

分布式锁 / 限流器 / 布隆过滤器。端口 **8085**，依赖本机 Redis `6379`。

## 为什么用 `redisson` 核心包，而不是 `redisson-spring-boot-starter`

starter 会绑定特定版本的 `redisson-spring-data`，与 Boot 3.4 的 spring-data-redis 容易出现
`Spring Data Redis version not supported` 之类的兼容问题。本项目只需要 `RedissonClient`，
手动建 Bean 反而更可控，也能看清连接池、超时、编解码器这些关键参数。

## 六个场景

| 场景 | 接口 | 观察点 |
|---|---|---|
| 分布式锁防超卖 | `POST /redisson/stock/concurrent?sku=&threads=20&perThread=5&useLock=true` | 加锁后库存正好到 0；`useLock=false` 会扣成负数 |
| 看门狗续期 | `GET /redisson/watchdog?holdMillis=7000` | 锁初始有效期 5s，持有 7s 后被自动续期，`lockedAtEnd=true` |
| 可重入 | `GET /redisson/reentrant` | 同一线程重复加锁，返回重入计数 2 |
| MultiLock | `GET /redisson/multilock?bizId=1` | 多把锁全部拿到才算成功 |
| 令牌桶限流 | `POST /redisson/limiter?permits=3&seconds=1&attempts=8` | 8 次尝试只放行 3 次 |
| 布隆过滤器 | `POST /redisson/bloom?exists=sku-1&exists=sku-2&probes=sku-1&probes=sku-9999` | 已加入判定存在，未加入判定不存在 |

## 运行

```powershell
mvn -B spring-boot:run -pl springboot-redisson
```

```powershell
# 初始化库存 10 件，然后 20 线程 × 5 次并发扣减
curl.exe -X POST "http://localhost:8085/redisson/stock/init?sku=A100&stock=10"
curl.exe -X POST "http://localhost:8085/redisson/stock/concurrent?sku=A100&useLock=true"
```

## 关键点

1. **`tryLock(waitTime, unit)` 只有两个参数时不指定 leaseTime**，意味着启用看门狗：
   Redisson 每 `lockWatchdogTimeout/3` 续期一次，业务执行多久锁就续多久。
   一旦显式传入 leaseTime，看门狗就关闭，业务超时会导致锁提前释放。
2. **释放锁必须校验持有者**：`if (lock.isHeldByCurrentThread()) lock.unlock()`。
   否则可能把别人刚拿到的锁解开（锁超时后重新被抢占的场景）。
3. **Redisson 锁底层是一个 Hash**（含线程标识 + 重入计数），配合 Lua 脚本保证原子性，
   这就是它既安全又可重入的原因。
4. **RedLock 需要 5 个互相独立的 Redis master**。本模块在单实例上开多把锁演示的是
   `RedissonMultiLock` 的 API 行为，不具备容错意义。
5. **布隆过滤器只会有假阳性**（判定存在但实际不存在），不会漏判不存在 ——
   所以适合放在缓存前面挡无效 ID，不能作为"数据一定存在"的依据。

## 测试

```powershell
mvn -B test -pl springboot-redisson
```

包含 7 个用例，其中看门狗用例需要 7 秒（故意超过 5s 的初始有效期）。
