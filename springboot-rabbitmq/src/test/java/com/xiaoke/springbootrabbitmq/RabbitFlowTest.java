package com.xiaoke.springbootrabbitmq;

import com.xiaoke.springbootrabbitmq.config.RabbitConfig;
import com.xiaoke.springbootrabbitmq.service.MessageProducer;
import com.xiaoke.springbootrabbitmq.service.MessageStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要本机 RabbitMQ（WSL2 apt 安装，5672 端口）。连不上则整类跳过。
 */
@SpringBootTest
class RabbitFlowTest {

    @Autowired
    private MessageProducer producer;

    @Autowired
    private MessageStore messageStore;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(reachable(), "RabbitMQ 未启动（localhost:5672），跳过集成测试");
        amqpAdmin.purgeQueue(RabbitConfig.DEMO_QUEUE, false);
        amqpAdmin.purgeQueue(RabbitConfig.DLQ_QUEUE, false);
        messageStore.clear();
    }

    @Test
    @DisplayName("direct：routingKey 完全匹配才入队")
    void directDelivers() {
        var outcome = producer.sendDirect("direct-hello");
        assertTrue(outcome.ack(), "broker 应返回发布确认");

        await().atMost(Duration.ofSeconds(10)).until(() ->
                messageStore.consumed().stream().anyMatch(e -> "direct-hello".equals(e.body())));
    }

    @Test
    @DisplayName("topic：order.* 命中 order.created，但不命中 order.created.paid")
    void topicWildcard() {
        producer.sendTopic("order.created", "topic-hit");
        producer.sendTopic("order.created.paid", "topic-miss");

        await().atMost(Duration.ofSeconds(10)).until(() ->
                messageStore.consumed().stream().anyMatch(e -> "topic-hit".equals(e.body())));

        // 多等一会儿，确认通配符没有误伤
        sleep(1_500);
        assertTrue(messageStore.consumed().stream().noneMatch(e -> "topic-miss".equals(e.body())),
                "order.* 不应匹配两段以上的 routingKey");
    }

    @Test
    @DisplayName("fanout：不看 routingKey，广播到绑定队列")
    void fanoutDelivers() {
        producer.sendFanout("fanout-hello");

        await().atMost(Duration.ofSeconds(10)).until(() ->
                messageStore.consumed().stream().anyMatch(e -> "fanout-hello".equals(e.body())));
    }

    @Test
    @DisplayName("headers：只有 header 匹配才入队")
    void headersMatching() {
        producer.sendHeaders("headers-hit", Map.of("type", "demo"));
        producer.sendHeaders("headers-miss", Map.of("type", "other"));

        await().atMost(Duration.ofSeconds(10)).until(() ->
                messageStore.consumed().stream().anyMatch(e -> "headers-hit".equals(e.body())));

        sleep(1_500);
        assertTrue(messageStore.consumed().stream().noneMatch(e -> "headers-miss".equals(e.body())),
                "header 不匹配的消息不应入队");
    }

    @Test
    @DisplayName("basicNack(requeue=false)：消息立刻进入死信队列")
    void nackGoesToDeadLetter() {
        producer.sendDirect("nack:bad-message");

        await().atMost(Duration.ofSeconds(10)).until(() ->
                messageStore.deadLettered().stream().anyMatch(e -> "nack:bad-message".equals(e.body())));
    }

    @Test
    @DisplayName("抛异常：手动 ack 下不重试，消息直接被拒绝进入死信队列")
    void failMessageRejectedToDeadLetter() {
        // 注意：spring.rabbitmq.listener.simple.retry.* 只在 AUTO ack 模式下生效。
        // 本模块用的是 manual ack（为了演示显式 basicAck），所以异常会直接被容器拒绝
        // （default-requeue-rejected=false → requeue=false），消息进入死信队列而不是重试。
        producer.sendDirect("fail:downstream-error");

        await().atMost(Duration.ofSeconds(20)).until(() ->
                messageStore.deadLettered().stream().anyMatch(e -> "fail:downstream-error".equals(e.body())));

        assertTrue(messageStore.consumed().stream()
                        .noneMatch(e -> "fail:downstream-error".equals(e.body())),
                "失败消息不应被计入正常消费");
    }

    @Test
    @DisplayName("延迟队列：TTL 到期后经死信交换机转到业务队列")
    void delayedDelivery() {
        producer.sendDelay("delay-hello");

        // 刚发出去还不应该被消费
        sleep(1_000);
        assertTrue(messageStore.consumed().stream().noneMatch(e -> "delay-hello".equals(e.body())),
                "延迟队列的消息不应立刻投递");

        await().atMost(Duration.ofSeconds(20)).until(() ->
                messageStore.consumed().stream().anyMatch(e -> "delay-hello".equals(e.body())));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5672), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
