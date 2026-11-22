package com.xiaoke.springbootrabbitmq.controller;

import com.xiaoke.springbootrabbitmq.config.RabbitConfig;
import com.xiaoke.springbootrabbitmq.service.MessageProducer;
import com.xiaoke.springbootrabbitmq.service.MessageStore;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/rabbit")
public class DemoController {

    private final MessageProducer producer;
    private final MessageStore messageStore;
    private final AmqpAdmin amqpAdmin;

    public DemoController(MessageProducer producer, MessageStore messageStore, AmqpAdmin amqpAdmin) {
        this.producer = producer;
        this.messageStore = messageStore;
        this.amqpAdmin = amqpAdmin;
    }

    @PostMapping("/direct")
    public MessageProducer.ConfirmOutcome direct(@RequestParam String message) {
        return producer.sendDirect(message);
    }

    @PostMapping("/topic")
    public MessageProducer.ConfirmOutcome topic(@RequestParam String routingKey,
                                                @RequestParam String message) {
        return producer.sendTopic(routingKey, message);
    }

    @PostMapping("/fanout")
    public MessageProducer.ConfirmOutcome fanout(@RequestParam String message) {
        return producer.sendFanout(message);
    }

    @PostMapping("/headers")
    public MessageProducer.ConfirmOutcome headers(@RequestParam String message,
                                                  @RequestParam(defaultValue = "demo") String type) {
        return producer.sendHeaders(message, Map.of("type", type));
    }

    @PostMapping("/delay")
    public MessageProducer.ConfirmOutcome delay(@RequestParam String message) {
        return producer.sendDelay(message);
    }

    /** 发送两种失败消息，观察重试与死信 */
    @PostMapping("/fail")
    public MessageProducer.ConfirmOutcome fail(@RequestParam(defaultValue = "nack") String mode,
                                               @RequestParam(defaultValue = "boom") String message) {
        String prefix = "nack".equalsIgnoreCase(mode) ? "nack:" : "fail:";
        return producer.sendDirect(prefix + message);
    }

    @GetMapping("/messages")
    public List<MessageStore.Entry> messages() {
        return messageStore.consumed();
    }

    @GetMapping("/dead-letters")
    public List<MessageStore.Entry> deadLetters() {
        return messageStore.deadLettered();
    }

    @PostMapping("/clear")
    public String clear() {
        messageStore.clear();
        return "cleared";
    }

    /** 队列深度：待消费数、消费者数 */
    @GetMapping("/queues")
    public Map<String, Object> queues() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String queue : List.of(RabbitConfig.DEMO_QUEUE, RabbitConfig.DLQ_QUEUE, RabbitConfig.DELAY_QUEUE)) {
            var info = amqpAdmin.getQueueInfo(queue);
            result.put(queue, info == null ? "not declared" : Map.of(
                    "messages", info.getMessageCount(),
                    "consumers", info.getConsumerCount()));
        }
        return result;
    }
}
