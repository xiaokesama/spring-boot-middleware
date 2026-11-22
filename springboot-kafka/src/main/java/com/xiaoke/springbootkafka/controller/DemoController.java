package com.xiaoke.springbootkafka.controller;

import com.xiaoke.springbootkafka.service.MessageStore;
import com.xiaoke.springbootkafka.service.ProducerService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/kafka")
public class DemoController {

    private final ProducerService producerService;
    private final MessageStore messageStore;
    private final KafkaAdmin kafkaAdmin;

    @Value("${kafka.topic.demo}")
    private String demoTopic;

    public DemoController(ProducerService producerService, MessageStore messageStore, KafkaAdmin kafkaAdmin) {
        this.producerService = producerService;
        this.messageStore = messageStore;
        this.kafkaAdmin = kafkaAdmin;
    }

    @PostMapping("/send")
    public ProducerService.SendOutcome send(@RequestParam String key,
                                            @RequestParam String message) {
        return producerService.send(key, message);
    }

    @PostMapping("/send-no-key")
    public ProducerService.SendOutcome sendNoKey(@RequestParam String message) {
        return producerService.sendWithoutKey(message);
    }

    /** 事务发送：多条消息要么全部可见，要么全部不可见 */
    @PostMapping("/send-tx")
    public List<ProducerService.SendOutcome> sendTx(@RequestParam List<String> messages) {
        return producerService.sendInTransaction(messages);
    }

    @GetMapping("/messages")
    public List<MessageStore.ReceivedMessage> messages() {
        return messageStore.received();
    }

    @GetMapping("/dead-letters")
    public List<MessageStore.ReceivedMessage> deadLetters() {
        return messageStore.deadLetters();
    }

    @PostMapping("/clear")
    public String clear() {
        messageStore.clear();
        return "cleared";
    }

    /** 查看 topic 的分区与副本分布 */
    @GetMapping("/partitions")
    public Map<String, Object> partitions() throws Exception {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        try (AdminClient admin = AdminClient.create(config)) {
            TopicDescription description = admin.describeTopics(List.of(demoTopic))
                    .allTopicNames().get(5, TimeUnit.SECONDS).get(demoTopic);

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("topic", demoTopic);
            result.put("partitionCount", description.partitions().size());
            result.put("partitions", description.partitions().stream()
                    .map(p -> Map.of("partition", p.partition(),
                            "leader", p.leader().id(),
                            "replicas", p.replicas().size()))
                    .toList());
            return result;
        }
    }
}
