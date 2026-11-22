# springboot-shardingsphere

ShardingSphere-JDBC 分库分表练习。端口 **8087**，**无需外部数据库**（两个 H2 内存库扮演物理库）。

## 拓扑

```
逻辑表 t_order
├── ds_0 (H2 mem)
│   ├── t_order_0   order_id % 2 == 0
│   └── t_order_1   order_id % 2 == 1
└── ds_1 (H2 mem)
    ├── t_order_0
    └── t_order_1

分库：user_id % 2  → ds_0 / ds_1
分表：order_id % 2 → t_order_0 / t_order_1
广播表：t_dict（每个库一份）
绑定表：t_order + t_order_item（同分片键）
```

规则写在 `ShardingDataSourceConfig` 里（**编程式 API**），业务代码只认识 `t_order` 这张逻辑表。

## 为什么用编程式 API，而不是 YAML

试过 `jdbc:shardingsphere:classpath:sharding.yaml` 的驱动方式：在 5.5.1 上 YAML 里的
`rules` / `mode` 解析不稳定，失败时只能看到一句 `Table or view 't_order' does not exist`，
连分片规则是否加载都无法确认。改成编程式后：

- 每一步都是显式代码，规则、算法、主键生成器一目了然
- 「建物理库 → 建物理表 → 建分片数据源」在同一个方法里，**顺序天然正确**
- 排查问题时能直接对着 `ShardingRuleConfiguration` 看

## 四个场景

| 场景 | 接口 | 观察点 |
|---|---|---|
| 分库分表 | `POST /seed?count=20` | 逻辑 20 条，物理上分散到 4 张表 |
| 雪花主键 | `POST /order?userId=1001` | 不传 `order_id`，由 SNOWFLAKE 生成并回填 |
| 绑定表 | `POST /order/item` + `GET /order/join` | `t_order` 与 `t_order_item` 在同一库内 JOIN |
| 广播表 | `POST /dict?type=status&value=CREATED` | 两个物理库各写一份 |
| 物理分布 | `GET /physical/stats` | 直连两个物理库统计每张表行数 |

## 运行

```powershell
mvn -B spring-boot:run -pl springboot-shardingsphere

curl.exe -X POST "http://localhost:8087/seed?count=20"
curl.exe "http://localhost:8087/physical/stats"
```

## 关键点（都是实际踩出来的）

1. **物理表名必须和 `actualDataNodes` 对得上**。规则写 `ds_${0..1}.t_order_${0..1}`，
   就必须真的存在 `t_order_0` / `t_order_1`；只建一张 `t_order` 会报表不存在。
2. **H2 的大小写是最大的坑**：
   - 未加引号的标识符会被 H2 转成大写（`ORDER_ID`），而 inline 表达式里写的是 `order_id`，
     ShardingSphere 严格比对后报 `Inline sharding algorithms expression ... do not match`；
   - 只用 `DATABASE_TO_LOWER=TRUE` 又会把 schema 变成 `public`，元数据加载找不到表；
   - 最终方案：DDL 用**引号小写标识符** + 连接串加 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`。
3. **`BeanFactoryPostProcessor` 也救不了的时序问题**：ShardingSphere 在建连接池时就加载元数据快照，
   所以物理表必须在数据源创建之前存在 —— 编程式配置把两者放在一个方法里最省心。
4. **绑定表**要求两张表分片键一致，否则 JOIN 会变成跨库笛卡尔积。
5. **广播表**适合字典、配置这类小表：写一次全库同步，查询不跨库。
6. **深翻页是分库难题**：`LIMIT 100000, 20` 需要各分片取 100020 条再归并，
   优化方向是「按分片键查询」或「游标代替 offset」。
7. 用 **5.5.1** 而不是 5.5.0：后者 POM 把未发布的 `shardingsphere-test-util` 声明为编译依赖，
   直接导致依赖解析失败。

## 测试

```powershell
mvn -B test -pl springboot-shardingsphere
```

5 个用例覆盖：雪花主键唯一性、物理分散、路由查询、绑定表 JOIN、广播表双写。
物理表由 `ShardingDataSourceConfig` 创建，测试之间会清空数据。
