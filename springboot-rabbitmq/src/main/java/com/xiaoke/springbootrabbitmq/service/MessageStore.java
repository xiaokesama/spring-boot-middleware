package com.xiaoke.springbootrabbitmq.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 消费结果的简易内存存储（仅用于观察与断言）。
 */
@Component
public class MessageStore {

    private static final int MAX_SIZE = 200;

    private final Deque<Entry> consumed = new ConcurrentLinkedDeque<>();
    private final Deque<Entry> deadLettered = new ConcurrentLinkedDeque<>();

    public void addConsumed(Entry entry) {
        push(consumed, entry);
    }

    public void addDeadLettered(Entry entry) {
        push(deadLettered, entry);
    }

    private void push(Deque<Entry> queue, Entry entry) {
        queue.addLast(entry);
        while (queue.size() > MAX_SIZE) {
            queue.pollFirst();
        }
    }

    public List<Entry> consumed() {
        return new ArrayList<>(consumed);
    }

    public List<Entry> deadLettered() {
        return new ArrayList<>(deadLettered);
    }

    public void clear() {
        consumed.clear();
        deadLettered.clear();
    }

    public record Entry(String queue, String body, String routingKey, int attempt) {
    }
}
