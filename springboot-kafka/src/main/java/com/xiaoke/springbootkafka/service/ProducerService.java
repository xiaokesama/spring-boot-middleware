package com.xiaoke.springbootkafka.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 生产者侧：普通发送与事务发送。
 *
 * <p><b>分区规则</b>：显式指定 key 时，Kafka 用 {@code murmur2(key) % 分区数} 选分区，
 * 所以同一个 key 永远落同一个分区 —— 这是「单 key 有序」的基础。
 * 不指定 key 时用粘性分区（一段时间内固定一个分区，凑批效率高）。
 *
 * <p><b>为什么要有两个模板</b>：给某个模板的 ProducerFactory 配上
 * {@code transaction-id-prefix} 之后，这个模板的所有 {@code send} 都必须发生在事务里，
 * 否则会抛 {@code No transaction is in process}。所以事务能力单独用
 * {@link #transactionalTemplate} 演示，普通发送仍然走 Boot 自动配置的 {@link #kafkaTemplate}。
 */
@Slf4j
@Service
public class ProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTemplate<String, String> transactionalTemplate;

    @Value("${kafka.topic.demo}")
    private String demoTopic;

    public ProducerService(KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.kafkaTemplate = kafkaTemplate;

        // 事务生产者必须开启幂等；transactional.id 前缀在每个实例上要唯一
        DefaultKafkaProducerFactory<String, String> txFactory = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
        txFactory.setTransactionIdPrefix("lab-tx-");
        this.transactionalTemplate = new KafkaTemplate<>(txFactory);
    }

    public SendOutcome send(String key, String payload) {
        try {
            SendResult<String, String> result = kafkaTemplate
                    .send(demoTopic, key, payload)
                    .get(5, TimeUnit.SECONDS);
            var metadata = result.getRecordMetadata();
            return new SendOutcome(demoTopic, metadata.partition(), metadata.offset(), key, payload);
        } catch (Exception e) {
            throw new IllegalStateException("发送失败: " + e.getMessage(), e);
        }
    }

    public SendOutcome sendWithoutKey(String payload) {
        try {
            SendResult<String, String> result = kafkaTemplate
                    .send(demoTopic, payload)
                    .get(5, TimeUnit.SECONDS);
            var metadata = result.getRecordMetadata();
            return new SendOutcome(demoTopic, metadata.partition(), metadata.offset(), null, payload);
        } catch (Exception e) {
            throw new IllegalStateException("发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 事务发送：{@code executeInTransaction} 内的多条消息「要么全部可见，要么全部不可见」
     * （消费端需要 read_committed 才能看到隔离效果）。
     *
     * <p>事务模板的 {@code transaction-id-prefix} 在构造时设置；
     * 生产多实例时前缀应带实例标识（如 {@code lab-tx-${HOSTNAME}-}），否则事务 ID 冲突。
     */
    public List<SendOutcome> sendInTransaction(List<String> payloads) {
        Boolean ok = transactionalTemplate.executeInTransaction(operations -> {
            for (String payload : payloads) {
                operations.send(demoTopic, payload.substring(0, Math.min(3, payload.length())), payload);
            }
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("事务提交失败");
        }
        // 事务提交后 offset 由 broker 分配，这里只回显发送内容
        return payloads.stream()
                .map(p -> new SendOutcome(demoTopic, -1, -1, null, p))
                .toList();
    }

    public record SendOutcome(String topic, int partition, long offset, String key, String payload) {
    }
}
