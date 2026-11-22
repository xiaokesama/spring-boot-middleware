package com.xiaoke.springbootclickhouse.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse 的数据访问。
 *
 * <p><b>写入一定要批量</b>：ClickHouse 每次 INSERT 都会生成一个新的 part，
 * 高频单条写入会迅速累积小 part，拖慢后台 merge，甚至触发 "Too many parts" 报错。
 * 官方建议每次至少几百到几万行。
 */
@Repository
public class EventRepository {

    private final JdbcTemplate jdbcTemplate;

    @Value("${clickhouse.database:lab}")
    private String database;

    @Value("${clickhouse.table:user_events}")
    private String table;

    public EventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void batchInsert(List<Event> events) {
        String sql = "INSERT INTO " + database + "." + table
                + " (event_date, event_time, user_id, event_type, amount) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, events, 1000, (ps, event) -> {
            ps.setDate(1, Date.valueOf(event.eventDate()));
            ps.setTimestamp(2, Timestamp.valueOf(event.eventTime()));
            ps.setLong(3, event.userId());
            ps.setString(4, event.eventType());
            ps.setBigDecimal(5, event.amount());
        });
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count() FROM " + database + "." + table, Long.class);
        return count == null ? 0 : count;
    }

    /** 按事件类型聚合：低基数类型用 LowCardinality 后 GROUP BY 会更快 */
    public List<Map<String, Object>> statsByType() {
        return jdbcTemplate.queryForList("""
                SELECT event_type,
                       count()          AS cnt,
                       sum(amount)      AS total,
                       avg(amount)      AS avg_amount,
                       uniqExact(user_id) AS uv
                FROM %s.%s
                GROUP BY event_type
                ORDER BY cnt DESC
                """.formatted(database, table));
    }

    /** 按天聚合：toDate / toStartOfDay 这类函数是 ClickHouse 的常用手法 */
    public List<Map<String, Object>> dailyTrend() {
        return jdbcTemplate.queryForList("""
                SELECT event_date,
                       count()     AS cnt,
                       sum(amount) AS total
                FROM %s.%s
                GROUP BY event_date
                ORDER BY event_date
                """.formatted(database, table));
    }

    /**
     * 查物化视图。
     *
     * <p>SummingMergeTree 只在后台 merge 时才真正合并相同排序键的行，
     * 所以查询时必须再 sum 一次，否则会看到多条未合并的记录。
     */
    public List<Map<String, Object>> materializedView() {
        return jdbcTemplate.queryForList("""
                SELECT event_date,
                       event_type,
                       sum(cnt)   AS cnt,
                       sum(total) AS total
                FROM %s.mv_daily_event
                GROUP BY event_date, event_type
                ORDER BY event_date DESC, cnt DESC
                LIMIT 20
                """.formatted(database));
    }

    /**
     * 清空数据。
     *
     * <p><b>物化视图必须单独清</b>：它是独立存储的 SummingMergeTree 表，
     * 只是「插入源表时自动写入」，并不会跟着源表的 TRUNCATE 一起清空。
     * 只清源表的话，MV 里会残留历史聚合值 —— 这是排查 ClickHouse 数据不一致时的常见坑。
     */
    public void truncate() {
        jdbcTemplate.execute("TRUNCATE TABLE " + database + "." + table);
        jdbcTemplate.execute("TRUNCATE TABLE " + database + ".mv_daily_event");
    }

    public record Event(LocalDate eventDate, LocalDateTime eventTime,
                        long userId, String eventType, BigDecimal amount) {
    }
}
