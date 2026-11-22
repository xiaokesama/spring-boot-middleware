package com.xiaoke.springbootrabbitmq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.HeadersExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明。
 *
 * <p>四种交换机的路由规则：
 * <table border="1">
 *   <tr><th>类型</th><th>规则</th><th>典型场景</th></tr>
 *   <tr><td>direct</td><td>routingKey 完全匹配</td><td>按业务类型分发</td></tr>
 *   <tr><td>topic</td><td>routingKey 通配（* 一个词，# 零到多个词）</td><td>按主题层级订阅</td></tr>
 *   <tr><td>fanout</td><td>忽略 routingKey，广播给所有绑定队列</td><td>通知、缓存刷新</td></tr>
 *   <tr><td>headers</td><td>按消息 header 匹配（x-match=all/any）</td><td>多维条件路由</td></tr>
 * </table>
 *
 * <p><b>延迟队列</b>：RabbitMQ 原生不支持延迟投递，经典做法是
 * 「TTL 队列 + 死信交换机」——消息在 {@code lab.queue.delay} 里躺够 TTL 后过期，
 * 自动被投递到死信交换机，再路由回业务队列。
 */
@Configuration
public class RabbitConfig {

    public static final String DIRECT_EXCHANGE = "lab.direct";
    public static final String TOPIC_EXCHANGE = "lab.topic";
    public static final String FANOUT_EXCHANGE = "lab.fanout";
    public static final String HEADERS_EXCHANGE = "lab.headers";
    public static final String DLX_EXCHANGE = "lab.dlx";

    public static final String DEMO_QUEUE = "lab.queue.demo";
    public static final String DLQ_QUEUE = "lab.queue.dlq";
    public static final String DELAY_QUEUE = "lab.queue.delay";

    public static final String RK_DEMO = "demo";
    public static final String RK_DELAY = "delay";
    public static final String RK_DEAD = "dead";

    // ---------------- 交换机 ----------------

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(DIRECT_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(TOPIC_EXCHANGE, true, false);
    }

    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange(FANOUT_EXCHANGE, true, false);
    }

    @Bean
    public HeadersExchange headersExchange() {
        return new HeadersExchange(HEADERS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    // ---------------- 队列 ----------------

    /**
     * 业务队列：绑定死信交换机。
     * 消息被拒绝（requeue=false）或过期后，会带着 {@link #RK_DEAD} 路由键进入死信交换机。
     */
    @Bean
    public Queue demoQueue() {
        return QueueBuilder.durable(DEMO_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(RK_DEAD)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    /** 延迟队列：消息在这里等 TTL 过期，然后死信到业务队列 */
    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .ttl(5_000)
                .deadLetterExchange(DIRECT_EXCHANGE)
                .deadLetterRoutingKey(RK_DEMO)
                .build();
    }

    // ---------------- 绑定 ----------------

    @Bean
    public Binding directBinding() {
        return BindingBuilder.bind(demoQueue()).to(directExchange()).with(RK_DEMO);
    }

    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(directExchange()).with(RK_DELAY);
    }

    /** topic 绑定：order.* 只匹配一层，order.created 命中，order.created.paid 不命中 */
    @Bean
    public Binding topicBinding() {
        return BindingBuilder.bind(demoQueue()).to(topicExchange()).with("order.*");
    }

    @Bean
    public Binding fanoutBinding() {
        return BindingBuilder.bind(demoQueue()).to(fanoutExchange());
    }

    /** headers 绑定：要求 header type=demo */
    @Bean
    public Binding headersBinding() {
        return BindingBuilder.bind(demoQueue()).to(headersExchange())
                .where("type").matches("demo");
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(RK_DEAD);
    }
}
