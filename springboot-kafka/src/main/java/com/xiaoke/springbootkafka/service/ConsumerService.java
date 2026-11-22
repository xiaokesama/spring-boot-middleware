package com.xiaoke.springbootkafka.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * 消费者侧：手动 ack + 重试 + 死信。
 *
 * <p><b>为什么关闭自动提交</b>：自动提交是「按时间提交」，消息还在处理时 offset 可能已经提交，
 * 进程一挂就丢消息。手动 ack 才能做到「处理成功才提交」，即至少一次（at-least-once）。
 *
 * <p><b>重试与死信</b>：
 * <ul>
 *   <li>payload 以 {@code retry:} 开头 → 抛 RuntimeException，按 FixedBackOff 重试 2 次后进 DLT</li>
 *   <li>payload 以 {@code poison:} 开头 → 抛 IllegalArgumentException，被标记为不可重试，直接进 DLT</li>
 * </ul>
 */
@Slf4j
@Service
public class ConsumerService {

    private final MessageStore messageStore;

    public ConsumerService(MessageStore messageStore) {
        this.messageStore = messageStore;
    }

    @KafkaListener(
            id = "lab-demo-listener",
            topics = "${kafka.topic.demo}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String payload = record.value();
        log.info("消费到消息 partition={} offset={} key={} payload={}",
                record.partition(), record.offset(), record.key(), payload);

        if (payload != null && payload.startsWith("poison:")) {
            // 不可重试异常 → 直接进死信，不占用重试次数
            throw new IllegalArgumentException("毒消息: " + payload);
        }
        if (payload != null && payload.startsWith("retry:")) {
            // 可重试异常 → 重试 2 次后进死信
            throw new IllegalStateException("下游暂时不可用: " + payload);
        }

        messageStore.addReceived(new MessageStore.ReceivedMessage(
                record.partition(), record.offset(), record.key(), payload));
        // 处理成功才提交 offset
        ack.acknowledge();
    }

    /** 死信队列的消费者：把失败消息收集起来，便于人工排查或补偿 */
    @KafkaListener(
            id = "lab-dlt-listener",
            topics = "${kafka.topic.dlt}",
            groupId = "lab-dlt-group"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.warn("收到死信 partition={} offset={} payload={}",
                record.partition(), record.offset(), record.value());
        messageStore.addDeadLetter(new MessageStore.ReceivedMessage(
                record.partition(), record.offset(), record.key(), record.value()));
        ack.acknowledge();
    }
}
