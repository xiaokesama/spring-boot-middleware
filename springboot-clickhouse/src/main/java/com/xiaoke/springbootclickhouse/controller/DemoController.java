package com.xiaoke.springbootclickhouse.controller;

import com.xiaoke.springbootclickhouse.repository.EventRepository;
import com.xiaoke.springbootclickhouse.service.EventSeeder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/events")
public class DemoController {

    private final EventRepository eventRepository;
    private final EventSeeder eventSeeder;

    public DemoController(EventRepository eventRepository, EventSeeder eventSeeder) {
        this.eventRepository = eventRepository;
        this.eventSeeder = eventSeeder;
    }

    /** 批量写入（ClickHouse 的正确用法） */
    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "5000") int count) {
        long start = System.currentTimeMillis();
        int inserted = eventSeeder.seed(count);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("costMillis", cost);
        result.put("total", eventRepository.count());
        result.put("rowsPerSecond", cost == 0 ? inserted : inserted * 1000L / cost);
        return result;
    }

    @GetMapping("/count")
    public long count() {
        return eventRepository.count();
    }

    /** 按事件类型聚合 */
    @GetMapping("/stats/type")
    public List<Map<String, Object>> byType() {
        return eventRepository.statsByType();
    }

    /** 按天聚合 */
    @GetMapping("/stats/daily")
    public List<Map<String, Object>> daily() {
        return eventRepository.dailyTrend();
    }

    /** 物化视图预聚合结果（对比 /stats/daily 的耗时） */
    @GetMapping("/stats/mv")
    public List<Map<String, Object>> materializedView() {
        return eventRepository.materializedView();
    }

    @PostMapping("/clear")
    public String clear() {
        eventRepository.truncate();
        return "truncated";
    }
}
