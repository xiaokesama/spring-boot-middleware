# springboot-clickhouse

ClickHouse 列式 OLAP 练习。端口 **8090**，依赖 ClickHouse `8123`（WSL2 安装）。

## 表设计

```
lab.user_events            MergeTree
├── PARTITION BY toYYYYMM(event_date)
└── ORDER BY (event_date, event_type, user_id)

lab.mv_daily_event         SummingMergeTree（物化视图，预聚合）
└── ORDER BY (event_date, event_type)
```

## 四个场景

| 场景 | 接口 | 观察点 |
|---|---|---|
| 批量写入 | `POST /events/seed?count=5000` | 返回写入耗时与 rows/s（几千行通常 < 100ms） |
| 按类型聚合 | `GET /events/stats/type` | `count / sum / avg / uniqExact(uv)` |
| 按天聚合 | `GET /events/stats/daily` | 走明细表，扫描量大 |
| 物化视图 | `GET /events/stats/mv` | 直接读预聚合结果，速度快很多 |
| 清空 | `POST /events/clear` | `TRUNCATE TABLE` |

## 运行

```powershell
mvn -B spring-boot:run -pl springboot-clickhouse

curl.exe -X POST "http://localhost:8090/events/seed?count=5000"
curl.exe "http://localhost:8090/events/stats/type"
curl.exe "http://localhost:8090/events/stats/daily"
curl.exe "http://localhost:8090/events/stats/mv"
```

也可以用命令行直连验证：

```bash
wsl -d Ubuntu-24.04 -u root -- clickhouse-client --query "SELECT count() FROM lab.user_events"
```

## 关键点

1. **`ORDER BY` 就是主键索引**，必须按查询最常用的过滤列来定；
   选错排序键，查询会退化成全表扫描。
2. **排序键不要求唯一**，重复插入不会报错 —— 这和 MySQL 主键语义完全不同，
   去重要靠 `ReplacingMergeTree` 或查询时 `DISTINCT`。
3. **写入必须批量**：每次 INSERT 生成一个 part，高频单条写会产生大量小 part，
   触发后台 merge 压力，甚至报 "Too many parts"。
4. **不适合单行 UPDATE / DELETE**：`ALTER TABLE ... UPDATE` 是异步重写 part，
   是批处理操作而不是事务操作。要更新就得用 `ReplacingMergeTree` 或重建分区。
5. **物化视图是"插入时触发"的独立表**：只对创建之后写入的数据生效；
   而且 SummingMergeTree 只在后台 merge 时合并相同排序键，查询时必须再 `sum()` 一次。
   特别注意：**`TRUNCATE` 源表不会清空物化视图**，两者要分别清。
6. **JDBC 连接的两个小坑**：ClickHouse 默认只监听 IPv4（用 `127.0.0.1` 而不是 `localhost`）；
   驱动默认开 LZ4 压缩但 classpath 没带 LZ4 本地库，会报
   `LZ4 is not supported`，加 `?compress=0` 或改用 gzip。
7. **端口别搞混**：ClickHouse HTTP 是 `8123`，Native TCP 是 `9000` ——
   而 MinIO 也是 `9000`。本模块统一走 8123。
8. `uniqExact` 是精确去重（吃内存），数据量大时用 `uniq`（近似，误差 ~0.5%）。

## 测试

```powershell
mvn -B test -pl springboot-clickhouse
```

3 个用例。测试开始前会探测 `localhost:8123`，**ClickHouse 没启动时自动跳过**而不是失败。
