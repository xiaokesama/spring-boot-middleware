package com.xiaoke.springbootkafka.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 消费结果的简易内存存储，用于 HTTP / 测试断言观察。
 *
 * <p>真实项目里不会这么干（会丢消息），这里只是为了把「消费到了什么」暴露出来。
 */
@Component
public class MessageStore {

    private static final int MAX_SIZE = 200;

    private final Deque<ReceivedMessage> received = new ConcurrentLinkedDeque<>();
    private final Deque<ReceivedMessage> deadLetters = new ConcurrentLinkedDeque<>();

    public void addReceived(ReceivedMessage message) {
        trim(received, message);
    }

    public void addDeadLetter(ReceivedMessage message) {
        trim(deadLetters, message);
    }

    private void trim(Deque<ReceivedMessage> queue, ReceivedMessage message) {
        queue.addLast(message);
        while (queue.size() > MAX_SIZE) {
            queue.pollFirst();
        }
    }

    public List<ReceivedMessage> received() {
        return new ArrayList<>(received);
    }

    public List<ReceivedMessage> deadLetters() {
        return new ArrayList<>(deadLetters);
    }

    public void clear() {
        received.clear();
        deadLetters.clear();
    }

    public record ReceivedMessage(int partition, long offset, String key, String payload) {
    }
}
