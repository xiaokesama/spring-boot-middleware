package com.xiaoke.springbootmetrics.service;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 四种最常用的指标类型在这里都有示例。
 *
 * <table border="1">
 *   <tr><th>类型</th><th>语义</th><th>典型用途</th></tr>
 *   <tr><td>Counter</td><td>只增不减</td><td>请求数、失败数</td></tr>
 *   <tr><td>Gauge</td><td>当前值，可增可减</td><td>队列长度、连接数、内存占用</td></tr>
 *   <tr><td>Timer</td><td>耗时分布 + 次数</td><td>接口 RT、下游调用耗时</td></tr>
 *   <tr><td>DistributionSummary</td><td>数值分布</td><td>订单金额、响应体大小</td></tr>
 * </table>
 */
@Slf4j
@Service
public class OrderService {

    private final Counter createdCounter;
    private final Counter failedCounter;
    private final DistributionSummary amountSummary;
    private final AtomicInteger pending = new AtomicInteger();

    public OrderService(MeterRegistry registry) {
        this.createdCounter = Counter.builder("lab.order.created")
                .description("成功创建的订单数")
                .register(registry);
        this.failedCounter = Counter.builder("lab.order.failed")
                .description("下单失败次数")
                .register(registry);
        this.amountSummary = DistributionSummary.builder("lab.order.amount")
                .description("订单金额分布")
                .baseUnit("yuan")
                // 输出分桶直方图，Prometheus 里可用 histogram_quantile 算分位数
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * 声明式埋点：{@code @Timed} 自动生成 lab.order.create.latency 计时器。
     * 方法抛异常时计时器照样记录，所以能反映「失败的慢请求」。
     */
    @Timed(value = "lab.order.create.latency", description = "下单耗时",
            percentiles = {0.5, 0.95, 0.99})
    @Counted(value = "lab.order.create.invocations", description = "下单方法被调用次数")
    public OrderResult create(long amountYuan, boolean forceFail) {
        pending.incrementAndGet();
        try {
            // 模拟业务耗时 20~80ms
            Thread.sleep(ThreadLocalRandom.current().nextLong(20, 80));

            if (forceFail) {
                failedCounter.increment();
                throw new IllegalStateException("模拟下单失败");
            }

            createdCounter.increment();
            amountSummary.record(amountYuan);
            return new OrderResult(UUID.randomUUID().toString(), amountYuan, "CREATED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failedCounter.increment();
            throw new IllegalStateException("线程被中断", e);
        } finally {
            pending.decrementAndGet();
        }
    }

    public int pendingCount() {
        return pending.get();
    }

    public double createdCount() {
        return createdCounter.count();
    }

    public double failedCount() {
        return failedCounter.count();
    }

    public record OrderResult(String orderId, long amount, String status) {
    }
}
