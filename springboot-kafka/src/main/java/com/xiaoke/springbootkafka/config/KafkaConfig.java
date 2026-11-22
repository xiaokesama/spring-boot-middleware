package com.xiaoke.springbootkafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 相关 Bean。
 *
 * <p>这里只做三件事，其余全部走 Spring Boot 自动配置：
 * <ol>
 *   <li>声明 topic（启动时由 KafkaAdmin 自动创建，已存在则跳过）</li>
 *   <li>配置「重试 + 死信」错误处理器</li>
 *   <li>（事务由 application.properties 里的 transaction-id-prefix 开启）</li>
 * </ol>
 */
@Configuration
public class KafkaConfig {

    @Value("${kafka.topic.demo}")
    private String demoTopic;

    @Value("${kafka.topic.dlt}")
    private String dltTopic;

    /** 分区数 3：直接决定同一个消费组最多能有 3 个消费者并行处理 */
    @Bean
    public NewTopic demoTopic() {
        return new NewTopic(demoTopic, 3, (short) 1);
    }

    @Bean
    public NewTopic dltTopic() {
        return new NewTopic(dltTopic, 3, (short) 1);
    }

    /**
     * 错误处理策略：可重试异常按固定间隔重试 2 次，仍然失败就投递到 DLT。
     *
     * <p>注意：把某个异常声明为 notRetryable 后，它<b>不会重试</b>，直接进死信。
     * 这能避免「参数校验失败」这类永久性错误在重试里空转。
     *
     * <p>Spring Boot 的自动配置会优先使用容器里的 {@code CommonErrorHandler} Bean，
     * 所以这里定义后不需要自己再建 ContainerFactory。
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // 分区沿用原消息的分区，保证 DLT 里同分区顺序仍然有序
                (record, ex) -> new org.apache.kafka.common.TopicPartition(dltTopic, record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(300L, 2L));
        // 毒消息不重试，直接进死信
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
