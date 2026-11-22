package com.xiaoke.springbootsharding.controller;

import com.xiaoke.springbootsharding.repository.OrderRepository;
import com.xiaoke.springbootsharding.service.PhysicalInspectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
public class DemoController {

    private final OrderRepository orderRepository;
    private final PhysicalInspectionService inspectionService;

    public DemoController(OrderRepository orderRepository,
                          PhysicalInspectionService inspectionService) {
        this.orderRepository = orderRepository;
        this.inspectionService = inspectionService;
    }

    /** 场景 1 + 2：插入订单，主键由雪花算法生成 */
    @PostMapping("/order")
    public Map<String, Object> createOrder(@RequestParam long userId,
                                           @RequestParam(defaultValue = "9.90") BigDecimal amount) {
        long orderId = orderRepository.insertOrder(userId, amount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("userId", userId);
        result.put("amount", amount);
        result.put("note", "orderId 由 SNOWFLAKE 生成；库由 user_id%2 决定，表由 order_id%2 决定");
        return result;
    }

    @PostMapping("/order/item")
    public String createItem(@RequestParam long orderId,
                             @RequestParam long userId,
                             @RequestParam(defaultValue = "book") String product,
                             @RequestParam(defaultValue = "1") int quantity) {
        orderRepository.insertItem(orderId, userId, product, quantity);
        return "item inserted";
    }

    /** 场景 3：绑定表 JOIN，两个表在同一物理库内关联 */
    @GetMapping("/order/join")
    public List<Map<String, Object>> join(@RequestParam long orderId, @RequestParam long userId) {
        return orderRepository.joinOrderItems(orderId, userId);
    }

    @GetMapping("/order")
    public List<Map<String, Object>> query(@RequestParam long orderId, @RequestParam long userId) {
        return orderRepository.findByOrderId(orderId, userId);
    }

    /** 场景 4：广播表，写一次两个库都有 */
    @PostMapping("/dict")
    public Map<String, Object> createDict(@RequestParam String type,
                                          @RequestParam String value) {
        orderRepository.insertDict(type, value);
        return inspectionService.stats();
    }

    @GetMapping("/dict")
    public List<Map<String, Object>> queryDict(@RequestParam String type) {
        return orderRepository.findDict(type);
    }

    /** 批量插入，用来看分片后的物理分布 */
    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "20") int count) {
        for (int i = 0; i < count; i++) {
            orderRepository.insertOrder(1000 + i, BigDecimal.valueOf(10 + i));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logicalCount", orderRepository.countOrders());
        result.put("physical", inspectionService.stats());
        return result;
    }

    @GetMapping("/physical/stats")
    public Map<String, Object> physicalStats() {
        return inspectionService.stats();
    }

    @PostMapping("/physical/clear")
    public String clear() {
        inspectionService.truncateAll();
        return "cleared";
    }
}
