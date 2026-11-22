package com.xiaoke.springbootredis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 五种基础结构 + Bitmap / HyperLogLog / Geo。
 *
 * <p>注意：每个测试都自己准备数据，<b>不要依赖别的测试先执行过</b>。
 * JUnit 5 不保证方法顺序，而且 Redis 里的数据可能被上次运行残留影响。
 */
@SpringBootTest
class TestRedis {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ---------------- String ----------------

    @Test
    @DisplayName("String：最基础的键值对")
    void testString() {
        redisTemplate.opsForValue().set("session:userId:123", "logged_in");

        Object value = redisTemplate.opsForValue().get("session:userId:123");
        assertEquals("logged_in", value);
    }

    // ---------------- Hash ----------------

    @Test
    @DisplayName("Hash：存对象的多个字段")
    void testHash() {
        redisTemplate.delete("user:123");
        redisTemplate.opsForHash().put("user:123", "name", "John");
        redisTemplate.opsForHash().put("user:123", "age", "30");
        redisTemplate.opsForHash().put("user:123", "email", "john@example.com");

        String name = (String) redisTemplate.opsForHash().get("user:123", "name");
        String age = (String) redisTemplate.opsForHash().get("user:123", "age");
        String email = (String) redisTemplate.opsForHash().get("user:123", "email");

        assertEquals("John", name);
        assertEquals("30", age);
        assertEquals("john@example.com", email);
    }

    // ---------------- List ----------------

    @Test
    @DisplayName("List：双端队列，可做简单消息队列")
    void testList() {
        redisTemplate.delete("taskQueue");
        redisTemplate.opsForList().rightPush("taskQueue", "task1");
        redisTemplate.opsForList().rightPush("taskQueue", "task2");
        redisTemplate.opsForList().leftPush("taskQueue", "task3");

        List<Object> tasks = redisTemplate.opsForList().range("taskQueue", 0, -1);
        assertEquals(List.of("task3", "task1", "task2"), tasks);
        assertEquals(3L, redisTemplate.opsForList().size("taskQueue"));

        // leftPop 弹栈：后进先出
        assertEquals("task3", redisTemplate.opsForList().leftPop("taskQueue"));
    }

    // ---------------- Set ----------------

    @Test
    @DisplayName("Set：去重集合，可做共同好友")
    void testSet() {
        redisTemplate.delete("user:123:friends");
        redisTemplate.opsForSet().add("user:123:friends", "user:456", "user:789");

        var members = redisTemplate.opsForSet().members("user:123:friends");
        assertNotNull(members);
        assertEquals(2, members.size());
        assertTrue(members.contains("user:456"));
    }

    // ---------------- Sorted Set ----------------

    @Test
    @DisplayName("ZSet：带分数排序，可做排行榜")
    void testSortedSet() {
        redisTemplate.delete("user:123:scores");
        redisTemplate.opsForZSet().add("user:123:scores", "score1", 10);
        redisTemplate.opsForZSet().add("user:123:scores", "score2", 20);
        redisTemplate.opsForZSet().add("user:123:scores", "score3", 30);

        var range = redisTemplate.opsForZSet().range("user:123:scores", 0, -1);
        assertNotNull(range);
        assertEquals(List.of("score1", "score2", "score3"), new java.util.ArrayList<>(range));
        assertEquals(20.0, redisTemplate.opsForZSet().score("user:123:scores", "score2"));
        assertEquals(3L, redisTemplate.opsForZSet().size("user:123:scores"));
    }

    // ---------------- Bitmap ----------------

    @Test
    @DisplayName("Bitmap：用 bit 记录用户签到，极致省内存")
    void testBitmap() {
        long userId = 9527L;
        redisTemplate.delete("active:2024-11-01");
        redisTemplate.delete("active:2024-11-02");
        redisTemplate.delete("active:2024-11-03");

        redisTemplate.opsForValue().setBit("active:2024-11-01", userId, true);
        redisTemplate.opsForValue().setBit("active:2024-11-02", userId, true);
        redisTemplate.opsForValue().setBit("active:2024-11-03", userId, false);

        assertEquals(Boolean.TRUE, redisTemplate.opsForValue().getBit("active:2024-11-01", userId));
        assertEquals(Boolean.TRUE, redisTemplate.opsForValue().getBit("active:2024-11-02", userId));
        assertEquals(Boolean.FALSE, redisTemplate.opsForValue().getBit("active:2024-11-03", userId));
    }

    // ---------------- HyperLogLog ----------------

    @Test
    @DisplayName("HyperLogLog：基数统计，1.2% 误差换固定 12KB 内存")
    void testHyperLogLog() {
        redisTemplate.delete("user:123:visited");
        redisTemplate.opsForHyperLogLog().add("user:123:visited", "page1", "page2", "page3");

        Long size = redisTemplate.opsForHyperLogLog().size("user:123:visited");
        assertNotNull(size);
        assertEquals(3L, size);
    }

    // ---------------- Geo ----------------

    @Test
    @DisplayName("Geo：地理位置，支持距离计算与半径查询")
    void testGeo() {
        initRestaurants();

        List<Point> points = redisTemplate.opsForGeo()
                .position("restaurants", "restaurant1", "restaurant2", "restaurant3");
        assertNotNull(points);
        assertEquals(3, points.size());
        points.forEach(point -> assertNotNull(point, "位置必须存在，检查是否先写入了 restaurant"));

        Distance distance = redisTemplate.opsForGeo().distance("restaurants", "restaurant1", "restaurant2");
        assertNotNull(distance);
        assertTrue(distance.getValue() > 0);
    }

    @Test
    @DisplayName("Geo：按半径查附近的地点")
    void testGeoRadius() {
        initRestaurants();

        Point center = new Point(13.361389, 38.115556);
        Distance radius = new Distance(500, Metrics.KILOMETERS);

        var results = redisTemplate.opsForGeo().radius("restaurants", new Circle(center, radius));

        assertNotNull(results);
        assertFalse(results.getContent().isEmpty(), "500 公里内至少应有 restaurant1");
    }

    private void initRestaurants() {
        redisTemplate.delete("restaurants");
        redisTemplate.opsForGeo().add("restaurants",
                new RedisGeoCommands.GeoLocation<>("restaurant1", new Point(13.361389, 38.115556)));
        redisTemplate.opsForGeo().add("restaurants",
                new RedisGeoCommands.GeoLocation<>("restaurant2", new Point(15.087269, 37.502669)));
        redisTemplate.opsForGeo().add("restaurants",
                new RedisGeoCommands.GeoLocation<>("restaurant3", new Point(15.087269, 37.502669)));
    }
}
