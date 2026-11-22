package com.xiaoke.springbootrabbitmq.service;

import com.rabbitmq.client.Channel;
import com.xiaoke.springbootrabbitmq.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 消费者：手动 ack、重试、拒绝入死信。
 *
 * <p>三种结局：
 * <ul>
 *   <li>正常消息 → basicAck，消息从队列删除</li>
 *   <li>{@code nack:} 开头 → basicNack(requeue=false)，立刻进入死信队列</li>
 *   <li>{@code fail:} 开头 → 抛异常，被重试拦截器重试 3 次后按 requeue=false 拒绝 → 死信</li>
 * </ul>
 *
 * <p>注意 {@code prefetch=10}：每个消费者最多 10 条未 ack 的消息在途，
 * 这是「消费能力」与「内存占用」之间的平衡点。
 */
@Slf4j
@Service
public class MessageConsumer {

    private final MessageStore messageStore;

    public MessageConsumer(MessageStore messageStore) {
        this.messageStore = messageStore;
    }

    @RabbitListener(queues = RabbitConfig.DEMO_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int attempt = deliveryCount(message);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();

        log.info("消费到消息 [{}] body={} 第 {} 次投递", routingKey, body, attempt);

        try {
            if (body.startsWith("nack:")) {
                // 明确拒绝且不重新入队 → 直接走死信交换机
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            if (body.startsWith("fail:")) {
                throw new IllegalStateException("下游处理失败: " + body);
            }

            messageStore.addConsumed(new MessageStore.Entry(RabbitConfig.DEMO_QUEUE, body, routingKey, attempt));
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 手动 ack 模式下，异常不会自动 reject（消息会一直处于 unacked 状态），
            // 必须自己 basicNack(requeue=false) 才能进入死信队列。
            // 注意：spring.rabbitmq.listener.simple.retry.* 也只在 AUTO ack 模式下生效。
            log.warn("处理失败，拒绝并进入死信: {}", e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /** 死信队列的消费者 */
    @RabbitListener(queues = RabbitConfig.DLQ_QUEUE)
    public void onDeadLetter(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.warn("收到死信 body={}", body);

        messageStore.addDeadLettered(new MessageStore.Entry(
                RabbitConfig.DLQ_QUEUE, body,
                message.getMessageProperties().getReceivedRoutingKey(),
                deliveryCount(message)));
        channel.basicAck(deliveryTag, false);
    }

    private int deliveryCount(Message message) {
        try {
            Object header = message.getMessageProperties().getHeaders().get("x-death");
            if (header instanceof java.util.List<?> deaths && !deaths.isEmpty()
                    && deaths.get(0) instanceof java.util.Map<?, ?> firstDeath
                    && firstDeath.get("count") instanceof Number number) {
                return number.intValue();
            }
        } catch (RuntimeException ignored) {
            // x-death 的结构由 broker 决定，解析失败不影响业务
        }
        return 1;
    }
}
