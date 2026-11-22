package com.xiaoke.springbootkafka;

import com.xiaoke.springbootkafka.service.MessageStore;
import com.xiaoke.springbootkafka.service.ProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用内嵌 Kafka 跑，不依赖本机 broker —— 这样 CI 里也能验证。
 * 需要连真实集群时，去掉 @EmbeddedKafka 与属性覆盖即可。
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(
        partitions = 3,
        topics = {"demo-topic", "demo-topic.DLT"},
        brokerProperties = {
                "transaction.state.log.replication.factor=1",
                "transaction.state.log.min.isr=1",
                "offsets.topic.replication.factor=1"
        }
)
class KafkaFlowTest {

    @Autowired
    private ProducerService producerService;

    @Autowired
    private MessageStore messageStore;

    @BeforeEach
    void clear() {
        messageStore.clear();
    }

    @Test
    @DisplayName("基础链路：发送后能被消费到")
    void messageConsumed() {
        producerService.send("k1", "hello-kafka");

        await().atMost(Duration.ofSeconds(20)).until(() ->
                messageStore.received().stream().anyMatch(m -> "hello-kafka".equals(m.payload())));
    }

    @Test
    @DisplayName("分区：相同 key 落到同一个分区，保证单 key 有序")
    void sameKeySamePartition() {
        var first = producerService.send("same-key", "p1");
        var second = producerService.send("same-key", "p2");

        assertEquals(first.partition(), second.partition());
    }

    @Test
    @DisplayName("不可重试异常：毒消息直接进死信队列")
    void poisonMessageGoesToDlt() {
        producerService.send("poison-key", "poison:bad-payload");

        await().atMost(Duration.ofSeconds(30)).until(() ->
                messageStore.deadLetters().stream()
                        .anyMatch(m -> "poison:bad-payload".equals(m.payload())));
    }

    @Test
    @DisplayName("可重试异常：重试 2 次后进入死信队列")
    void retryableMessageEndsInDlt() {
        producerService.send("retry-key", "retry:downstream-down");

        await().atMost(Duration.ofSeconds(30)).until(() ->
                messageStore.deadLetters().stream()
                        .anyMatch(m -> "retry:downstream-down".equals(m.payload())));

        // 正常消息不应被误判
        assertTrue(messageStore.received().stream()
                .noneMatch(m -> "retry:downstream-down".equals(m.payload())));
    }

    @Test
    @DisplayName("事务：一个事务里的多条消息要么全部可见要么全部不可见")
    void transactionDeliversAllOrNothing() {
        producerService.sendInTransaction(List.of("tx-1", "tx-2"));

        await().atMost(Duration.ofSeconds(20)).until(() -> {
            var payloads = messageStore.received().stream().map(MessageStore.ReceivedMessage::payload).toList();
            return payloads.contains("tx-1") && payloads.contains("tx-2");
        });
    }
}
