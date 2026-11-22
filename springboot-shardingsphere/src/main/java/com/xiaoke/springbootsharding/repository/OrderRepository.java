package com.xiaoke.springbootsharding.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

/**
 * 业务代码完全感知不到分库分表：写的还是 t_order 这张「逻辑表」。
 */
@Repository
public class OrderRepository {

    private static final String INSERT_ORDER =
            "INSERT INTO t_order (user_id, status, amount) VALUES (?, ?, ?)";
    private static final String INSERT_ITEM =
            "INSERT INTO t_order_item (order_id, user_id, product_name, quantity) VALUES (?, ?, ?, ?)";
    private static final String INSERT_DICT =
            "INSERT INTO t_dict (dict_type, dict_value) VALUES (?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 不传 order_id，由 ShardingSphere 的 SNOWFLAKE 算法生成；
     * 通过 GeneratedKeyHolder 把生成的主键取回来。
     */
    public long insertOrder(long userId, BigDecimal amount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_ORDER, new String[]{"order_id"});
            ps.setLong(1, userId);
            ps.setString(2, "CREATED");
            ps.setBigDecimal(3, amount);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("没有取到分片主键");
        }
        return key.longValue();
    }

    public void insertItem(long orderId, long userId, String productName, int quantity) {
        jdbcTemplate.update(INSERT_ITEM, orderId, userId, productName, quantity);
    }

    public void insertDict(String type, String value) {
        jdbcTemplate.update(INSERT_DICT, type, value);
    }

    public List<Map<String, Object>> findByOrderId(long orderId, long userId) {
        return jdbcTemplate.queryForList(
                "SELECT order_id, user_id, status, amount FROM t_order WHERE order_id = ? AND user_id = ?",
                orderId, userId);
    }

    /** 绑定表 JOIN：两张表的分片键一致，路由到同一个库的同一对物理表 */
    public List<Map<String, Object>> joinOrderItems(long orderId, long userId) {
        return jdbcTemplate.queryForList("""
                SELECT o.order_id, o.amount, i.product_name, i.quantity
                FROM t_order o
                JOIN t_order_item i ON o.order_id = i.order_id
                WHERE o.order_id = ? AND o.user_id = ?
                """, orderId, userId);
    }

    public List<Map<String, Object>> findDict(String type) {
        return jdbcTemplate.queryForList(
                "SELECT dict_type, dict_value FROM t_dict WHERE dict_type = ?", type);
    }

    public List<Map<String, Object>> countOrders() {
        return jdbcTemplate.queryForList("SELECT COUNT(*) AS cnt FROM t_order");
    }
}
