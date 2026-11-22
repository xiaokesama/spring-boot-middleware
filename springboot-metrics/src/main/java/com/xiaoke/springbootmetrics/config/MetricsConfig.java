package com.xiaoke.springbootmetrics.config;

import com.xiaoke.springbootmetrics.service.OrderService;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标注册。
 *
 * <p>Micrometer 是「指标门面」（类似 SLF4J 之于日志）：
 * 业务代码只面向 {@link MeterRegistry} 编程，具体暴露成 Prometheus 格式
 * 还是其它后端的格式，由运行时引入的 registry 实现决定（这里是 micrometer-registry-prometheus）。
 */
@Configuration
public class MetricsConfig {

    /**
     * 没有这个 Bean，{@code @Timed} 注解不会生效——注解本身不会自己织入。
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    /**
     * Gauge 是「可增可减、只反映当前值」的指标，适合队列长度、连接数这类瞬时状态。
     *
     * <p>注意 Gauge 保存的是对目标对象的<b>弱引用</b>，目标对象被回收后指标会消失，
     * 所以这里注册的是容器里的单例 Bean，而不是临时 new 出来的对象。
     */
    @Bean
    public Gauge pendingOrderGauge(MeterRegistry registry, OrderService orderService) {
        return Gauge.builder("lab.order.pending", orderService, OrderService::pendingCount)
                .description("当前处理中的订单数")
                .register(registry);
    }
}
