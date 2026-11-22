package com.xiaoke.springbootredisson.service;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁的四个典型场景。
 *
 * <p><b>为什么需要分布式锁</b>：单机 {@code synchronized} 只能锁住一个 JVM，
 * 多实例部署时库存、订单号这类「读-改-写」必须用分布式锁或原子操作串行化。
 *
 * <p><b>Redisson 的锁是什么</b>：本质是 Redis 上的一个 Hash，配合 Lua 脚本保证
 * 「判断 + 设置」的原子性；value 里记录持有线程标识和重入次数，
 * 所以它既能在释放时校验持有者，也天然支持可重入。
 */
@Slf4j
@Service
public class DistributedLockService {

    private static final String LOCK_STOCK = "lab:lock:stock:";
    private static final String STOCK_KEY = "lab:stock:";

    private final RedissonClient redisson;

    public DistributedLockService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 初始化库存（覆盖写） */
    public long initStock(String sku, long stock) {
        RAtomicLong counter = redisson.getAtomicLong(STOCK_KEY + sku);
        counter.set(stock);
        return counter.get();
    }

    public long currentStock(String sku) {
        return redisson.getAtomicLong(STOCK_KEY + sku).get();
    }

    /**
     * 场景 1：用分布式锁防止超卖。
     *
     * <p>锁内做「读库存 → 判断 → 扣减」，中间故意 sleep 几毫秒把竞态窗口放大；
     * 如果去掉锁，并发下 {@code currentStock} 会出现负数。
     *
     * <p>注意 {@code tryLock(waitTime, unit)} 的两个参数：
     * <ul>
     *   <li>waitTime：最多等多久拿锁，拿不到就放弃（避免请求无限堆积）</li>
     *   <li>未指定 leaseTime → 启用看门狗自动续期，业务执行多久锁就续多久</li>
     * </ul>
     */
    public DeductResult deduct(String sku, int quantity, long simulateWorkMillis) {
        RLock lock = redisson.getLock(LOCK_STOCK + sku);
        boolean locked = false;
        long start = System.currentTimeMillis();
        try {
            locked = lock.tryLock(3, TimeUnit.SECONDS);
            if (!locked) {
                return new DeductResult(false, "获取锁失败（等待 3s 超时）", currentStock(sku), 0);
            }
            long stock = currentStock(sku);
            if (stock < quantity) {
                return new DeductResult(false, "库存不足", stock, System.currentTimeMillis() - start);
            }
            if (simulateWorkMillis > 0) {
                Thread.sleep(simulateWorkMillis);
            }
            redisson.getAtomicLong(STOCK_KEY + sku).addAndGet(-quantity);
            return new DeductResult(true, "扣减成功", currentStock(sku), System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeductResult(false, "线程被中断", currentStock(sku), 0);
        } finally {
            // 必须判断持有者再释放，否则可能把别人刚拿到的锁解开
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 场景 2：看门狗自动续期。
     *
     * <p>不传 leaseTime 时，锁的初始有效期 = lockWatchdogTimeout（本模块配成 5s），
     * 后台线程每 1/3 周期续期一次。这里持有 8s（> 5s），分别在 1s 和 7s 采样，
     * 第二次仍然 isLocked=true，就说明续期生效了。
     */
    public WatchdogResult observeWatchdog(String lockName, long holdMillis) throws InterruptedException {
        RLock lock = redisson.getLock("lab:lock:watchdog:" + lockName);
        boolean locked = lock.tryLock(); // 无参 = 立即尝试 + 看门狗续期
        if (!locked) {
            return new WatchdogResult(false, false, -1, false, -1, "锁被占用");
        }
        try {
            Thread.sleep(1_000);
            boolean lockedAt1s = lock.isLocked();
            long ttlAt1s = lock.remainTimeToLive();

            long remain = holdMillis - 1_000;
            if (remain > 0) {
                Thread.sleep(remain);
            }
            boolean lockedAtEnd = lock.isLocked();
            long ttlAtEnd = lock.remainTimeToLive();
            return new WatchdogResult(true, lockedAt1s, ttlAt1s, lockedAtEnd, ttlAtEnd,
                    "holdMillis=" + holdMillis + "，watchdog=5000ms");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 场景 3：可重入。同一线程再次加锁只是把重入计数 +1，不会自己把自己锁死。
     */
    public int reentrant(String lockName) {
        RLock lock = redisson.getLock("lab:lock:reentrant:" + lockName);
        lock.lock();
        try {
            lock.lock();
            try {
                return lock.getHoldCount(); // 期望 2
            } finally {
                lock.unlock();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 场景 4：MultiLock（3.x 中 RedLock 的替代品）。
     *
     * <p>多个独立锁必须全部拿到才算成功。注意：真正意义上的 RedLock 需要
     * 5 个<b>互相独立</b>的 Redis master；这里同一个实例上开多把锁只是演示 API 行为，
     * 不具备容错意义。
     */
    public String multiLock(String bizId) {
        RLock lock1 = redisson.getLock("lab:lock:multi:order:" + bizId);
        RLock lock2 = redisson.getLock("lab:lock:multi:stock:" + bizId);
        var multiLock = redisson.getMultiLock(lock1, lock2);
        boolean locked = false;
        try {
            locked = multiLock.tryLock(1, 3, TimeUnit.SECONDS);
            return locked ? "两把锁全部获取成功" : "获取失败";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "线程被中断";
        } finally {
            if (locked) {
                multiLock.unlock();
            }
        }
    }

    /** 场景 5 的辅助：演示不用锁时并发扣减会出问题（用于对比） */
    public List<Long> concurrentDeduct(String sku, int threads, int perThread, boolean useLock) {
        List<Long> snapshots = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    if (useLock) {
                        deduct(sku, 1, 2);
                    } else {
                        unsafeDeduct(sku, 1);
                    }
                }
            });
            workers.add(t);
            t.start();
        }
        workers.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        snapshots.add(currentStock(sku));
        return snapshots;
    }

    private void unsafeDeduct(String sku, int quantity) {
        long stock = currentStock(sku);
        if (stock >= quantity) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            redisson.getAtomicLong(STOCK_KEY + sku).addAndGet(-quantity);
        }
    }

    public record DeductResult(boolean success, String message, long stockAfter, long costMillis) {
    }

    public record WatchdogResult(boolean acquired, boolean lockedAt1s, long ttlAt1s,
                                 boolean lockedAtEnd, long ttlAtEnd, String note) {
    }
}
