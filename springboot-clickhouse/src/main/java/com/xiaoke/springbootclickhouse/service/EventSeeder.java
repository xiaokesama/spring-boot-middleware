package com.xiaoke.springbootclickhouse.service;

import com.xiaoke.springbootclickhouse.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 造一批测试数据：近 7 天、4 种事件类型、随机用户与金额。
 */
@Service
public class EventSeeder {

    private static final List<String> EVENT_TYPES =
            List.of("login", "view", "add_cart", "pay");

    private final EventRepository eventRepository;

    public EventSeeder(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public int seed(int count) {
        List<EventRepository.Event> events = new ArrayList<>(count);
        LocalDate today = LocalDate.now();

        for (int i = 0; i < count; i++) {
            LocalDate date = today.minusDays(ThreadLocalRandom.current().nextInt(7));
            LocalDateTime time = date.atTime(LocalTime.of(
                    ThreadLocalRandom.current().nextInt(24),
                    ThreadLocalRandom.current().nextInt(60),
                    ThreadLocalRandom.current().nextInt(60)));
            events.add(new EventRepository.Event(
                    date,
                    time,
                    ThreadLocalRandom.current().nextLong(1, 501),
                    EVENT_TYPES.get(ThreadLocalRandom.current().nextInt(EVENT_TYPES.size())),
                    BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(1, 500))
                            .setScale(2, RoundingMode.HALF_UP)));
        }

        eventRepository.batchInsert(events);
        return count;
    }
}
