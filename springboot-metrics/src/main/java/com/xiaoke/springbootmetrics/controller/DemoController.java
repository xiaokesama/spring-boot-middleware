package com.xiaoke.springbootmetrics.controller;

import com.xiaoke.springbootmetrics.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/order")
public class DemoController {

    private final OrderService orderService;
    private final MeterRegistry meterRegistry;

    public DemoController(OrderService orderService, MeterRegistry meterRegistry) {
        this.orderService = orderService;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestParam(defaultValue = "99") long amount,
                                         @RequestParam(defaultValue = "false") boolean fail) {
        try {
            return ResponseEntity.ok(orderService.create(amount, fail));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** 直接读 MeterRegistry，看看业务埋点产生了哪些指标 */
    @GetMapping("/meters")
    public Map<String, Object> meters() {
        Map<String, Object> result = new LinkedHashMap<>();
        Search.in(meterRegistry).name(n -> n.startsWith("lab.")).meters().forEach(meter -> {
            String value;
            try {
                value = String.valueOf(meter.measure().iterator().next().getValue());
            } catch (Exception e) {
                value = "n/a";
            }
            result.put(meter.getId().getName(), Map.of(
                    "type", meter.getId().getType().name(),
                    "tags", meter.getId().getTags().toString(),
                    "value", value));
        });
        return result;
    }
}
