package com.xiaoke.springbootrabbitmq.service;

import com.xiaoke.springbootrabbitmq.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 生产者：四种交换机 + 延迟队列 + 发布确认。
 *
 * <p><b>发布确认（publisher confirm）</b>：消息到达交换机会先收到 ack/nack，
 * 与「消费端 ack」是两回事——前者保证消息到了 broker，后者保证消息被处理。
 * 这里用 {@link CorrelationData} 同步等待确认，方便演示；生产环境一般异步处理。
 */
@Slf4j
@Service
public class MessageProducer {

    private final RabbitTemplate rabbitTemplate;

    public MessageProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public ConfirmOutcome sendDirect(String payload) {
        return send(RabbitConfig.DIRECT_EXCHANGE, RabbitConfig.RK_DEMO, payload, Map.of());
    }

    public ConfirmOutcome sendTopic(String routingKey, String payload) {
        return send(RabbitConfig.TOPIC_EXCHANGE, routingKey, payload, Map.of());
    }

    public ConfirmOutcome sendFanout(String payload) {
        return send(RabbitConfig.FANOUT_EXCHANGE, "", payload, Map.of());
    }

    public ConfirmOutcome sendHeaders(String payload, Map<String, Object> headers) {
        return send(RabbitConfig.HEADERS_EXCHANGE, "", payload, headers);
    }

    /** 发送到延迟队列：5 秒 TTL 到期后自动转到业务队列 */
    public ConfirmOutcome sendDelay(String payload) {
        return send(RabbitConfig.DIRECT_EXCHANGE, RabbitConfig.RK_DELAY, payload, Map.of());
    }

    private ConfirmOutcome send(String exchange, String routingKey, String payload,
                                Map<String, Object> headers) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());

        MessagePostProcessor postProcessor = message -> {
            headers.forEach((k, v) -> message.getMessageProperties().setHeader(k, v));
            return message;
        };

        rabbitTemplate.convertAndSend(exchange, routingKey, payload, postProcessor, correlation);

        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
            return new ConfirmOutcome(exchange, routingKey, payload,
                    confirm.isAck(), confirm.getReason());
        } catch (Exception e) {
            return new ConfirmOutcome(exchange, routingKey, payload, false, "等待确认失败: " + e.getMessage());
        }
    }

    public record ConfirmOutcome(String exchange, String routingKey, String payload,
                                 boolean ack, String reason) {
    }
}
