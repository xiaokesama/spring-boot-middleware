# spring-boot-middleware-lab

Spring Boot 3.4 / Java 17 的**中间件练习合集**：一个模块一个中间件，每个模块包含
可运行的典型场景、`@SpringBootTest` 测试和踩坑笔记。

目标不是"跑通 Hello World"，而是把每个中间件**最容易踩的坑**做成可复现的实验。

---

## 一、模块一览

| 模块 | 端口 | 中间件 | 依赖外部服务 | 主要场景 |
|---|---|---|---|---|
| `springboot-basics` | — | 无（纯 Java） | 无 | JDK 动态代理手写 AOP、自定义注解 + 反射、ThreadLocal |
| `springboot-redis` | 8080 | Redis | Redis 6379 | 五种基础结构、Bitmap / HyperLogLog / Geo |
| `springboot-minio` | 8081 | MinIO | MinIO 9000 | 上传、列目录、预签名 URL |
| `springboot-jwt` | 8082 | JWT | 无 | 令牌签发与校验（学习中） |
| `springboot-httpclient` | — | Apache HttpClient | 无（测试内嵌 HttpServer） | GET / POST、状态码与响应体处理 |
| `springboot-redisson` | 8085 | Redisson | Redis 6379 | 分布式锁防超卖、看门狗续期、可重入、MultiLock、限流器、布隆过滤器 |
| `springboot-cache` | 8086 | Caffeine + Spring Cache | 无 | 声明式缓存、缓存穿透 / 击穿 / 雪崩对照实验 |
| `springboot-shardingsphere` | 8087 | ShardingSphere-JDBC | 无（H2 内存库） | 分库分表、雪花主键、绑定表、广播表、物理分布验证 |
| `springboot-metrics` | 8088 | Micrometer + Prometheus | 无 | Counter / Gauge / Timer / DistributionSummary、`@Timed`、Prometheus 端点 |
| `springboot-kafka` | 8089 | Kafka | Kafka 9092 | 生产消费、分区与有序、消费组、手动 ack、重试、死信、事务 |
| `springboot-clickhouse` | 8090 | ClickHouse | ClickHouse 8123 | MergeTree 建表、批量写入、聚合查询、物化视图 |
| `springboot-rabbitmq` | 8091 | RabbitMQ | RabbitMQ 5672 | 四种交换机、手动 ack、重试与死信、延迟队列、发布确认 |
| `springboot-nacos`（legacy） | 8080 | Nacos 配置中心 | Nacos 8848 | `@NacosPropertySource`、`@NacosValue` 热更新、原生 ConfigService 监听 |

> `springboot-nacos` 停留在 Spring Boot 2.6.3（`nacos-config-spring-boot-starter 0.2.x`
> 与 Boot 3 不兼容），因此**不参与**根聚合构建，需要单独进入该目录构建运行。

## 二、环境要求

- JDK 17
- Maven 3.8+
- 中间件（本机或 WSL2）：Redis、ClickHouse、RabbitMQ、Kafka
- 可选：MinIO、Nacos

### 本机已就绪的服务

| 服务 | 端口 | 说明 |
|---|---|---|
| Redis | 6379 | 已在本机运行 |
| MySQL | 3306 | 已在本机运行（当前模块暂未使用） |
| ClickHouse | 8123 (HTTP) / 9000 (Native) | WSL2 中安装 |
| RabbitMQ | 5672 / 15672 (管理台) | WSL2 中安装，guest/guest |
| Kafka | 9092 | WSL2 中安装（KRaft 单节点） |

## 三、一键安装中间件（WSL2）

脚本在 `scripts/wsl/`，**可重复执行**（已安装时只做配置校正与重启）：

```powershell
# 在仓库根目录执行
wsl -d Ubuntu-24.04 -u root -- bash /mnt/c/Users/11932/PycharmProjects/spring-boot-middleware-lab/scripts/wsl/install-clickhouse.sh
wsl -d Ubuntu-24.04 -u root -- bash /mnt/c/Users/11932/PycharmProjects/spring-boot-middleware-lab/scripts/wsl/install-rabbitmq.sh
wsl -d Ubuntu-24.04 -u root -- bash /mnt/c/Users/11932/PycharmProjects/spring-boot-middleware-lab/scripts/wsl/install-kafka.sh
```

查看状态：

```powershell
wsl -d Ubuntu-24.04 -u root -- bash /mnt/c/Users/11932/PycharmProjects/spring-boot-middleware-lab/scripts/wsl/status.sh
```

### 两个踩过的坑（脚本里已处理）

1. **RabbitMQ 默认只监听 IPv6（`[::]:5672`）**，而 WSL2 的 localhost 端口转发只认 IPv4，
   Windows 侧表现为"连接被拒"。脚本里显式写了 `listeners.tcp.default = 0.0.0.0:5672`。
2. **Kafka 下载源**：阿里/清华/中科大镜像对 3.9.0 都是 404，回退到 `archive.apache.org` 只有
   ~1MB/min。最终用**华为云镜像**（实测 25MB/s）。脚本按 `华为云 → dlcdn → archive` 顺序回退。

## 四、构建与运行

```powershell
# 全量构建（跳过测试）
mvn -B clean package -DskipTests

# 只构建某个模块（含依赖）
mvn -B -pl springboot-kafka -am package -DskipTests

# 运行某个模块
mvn -B spring-boot:run -pl springboot-kafka

# 跑某个模块的测试
mvn -B test -pl springboot-cache
```

> 各模块端口已错开，可同时启动。没有外部依赖的模块（`basics` / `cache` / `shardingsphere` /
> `metrics` / `httpclient`）可以直接跑。依赖外部服务的模块在服务未启动时会告警，但不会拖垮构建。

## 五、目录约定

```
springboot-<middleware>/
├── README.md                     # 概念要点 + 场景 + curl 示例 + 踩坑
├── pom.xml                       # 继承根 POM，只声明自己需要的依赖
└── src/
    ├── main/java/com/xiaoke/...  # config / service / repository / controller
    ├── main/resources/           # application.properties（端口 + 中间件参数）
    └── test/java/...             # @SpringBootTest 行为测试
```

- 版本统一在根 POM 的 `<properties>` + `<dependencyManagement>` 管理。
- 端口按上表分配，避免同时启动冲突。
- 需要外部服务的集成测试用 `Assumptions.assumeTrue(...)` 做可达性探测，服务没起时**跳过而不是失败**。

## 六、版本策略

| 项目 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 3.4.0 | 统一父 POM |
| Java | 17 | |
| Redisson | 3.35.0 | 只引核心包，手动建 `RedissonClient`，避免 starter 与 Boot 3.4 的 spring-data 版本冲突 |
| ShardingSphere-JDBC | 5.5.1 | 5.5.0 的 POM 误把 `shardingsphere-test-util` 声明为编译依赖且未发布，5.5.1 修正为 test scope |
| ClickHouse JDBC | 0.6.5 | 走 HTTP 8123 |
| spring-kafka | Boot 托管 | 测试用 `spring-kafka-test` 内嵌 broker |

## 七、踩坑记录（本轮真实遇到的）

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | Kafka 起不来，`advertised.listeners cannot use the nonroutable meta-address 0.0.0.0` | 默认 KRaft 配置里 `advertised.listeners` 是注释态，Kafka 从 `listeners` 推导出 0.0.0.0 | 显式写 `advertised.listeners=PLAINTEXT://localhost:9092`，并且 `CONTROLLER` 监听器要绑 `127.0.0.1` 而不是 `0.0.0.0` |
| 2 | Windows 连不上 WSL 里的 RabbitMQ（5672 拒绝） | RabbitMQ 默认只监听 `[::]:5672`（IPv6），WSL2 localhost 转发只认 IPv4 | `listeners.tcp.default = 0.0.0.0:5672` |
| 3 | RabbitMQ 管理台 15672 不监听 | `rabbitmq-plugins enable --offline` 只在下次启动生效 | 启用插件后必须 restart 一次 |
| 4 | Kafka 下载极慢（1MB/min） | 阿里/清华/中科大镜像对 3.9.0 都是 404，回退到 archive.apache.org | 用华为云镜像（25MB/s），脚本按镜像列表回退 |
| 5 | ShardingSphere 报 `Table or view 't_order' does not exist` | 物理表建成了逻辑表名；以及 H2 元数据列名大写与 inline 表达式小写不匹配 | 物理表名对齐 `actualDataNodes`；DDL 用引号小写 + `CASE_INSENSITIVE_IDENTIFIERS=TRUE` |
| 6 | ShardingSphere 分片规则怎么都不生效 | 5.5.1 的 YAML 驱动方式解析 `rules`/`mode` 不稳定，元数据里连数据库都没注册 | 改用**编程式 API**（`ShardingSphereDataSourceFactory`），并把建表放在建数据源之前 |
| 7 | 测试里 `ApplicationRunner` 不执行，物理表凭空消失 | `ApplicationRunner` 在 `@SpringBootTest` 中的执行时机不保证 | 需要「上下文初始化期」动作时用 `InitializingBean` / `BeanFactoryPostProcessor` |
| 8 | Redis 模块 18 个用例互相依赖，随机挂 | 原测试 `testGetGeo` 等依赖 `testSetGeo` 先执行（JUnit 5 不保证顺序） | 每个用例自己准备数据 |
| 9 | `RedisTemplate<String,String>` 注入失败 | 容器里是 `RedisTemplate<String,Object>`，泛型按类型匹配 | 统一泛型 + `StringRedisSerializer` |
| 10 | MinIO 密钥明文在仓库里 | 直接把 accessKey/secretKey 写进 properties | 改成 `${MINIO_ACCESS_KEY:minioadmin}` 环境变量占位 |
| 11 | Kafka 普通发送报 `No transaction is in process` | 给主模板配了 `transaction-id-prefix` 后，所有 `send` 都必须在事务内 | 事务用独立的 `KafkaTemplate`（单独 `ProducerFactory` + 前缀） |
| 12 | RabbitMQ 监听器抛异常后消息既不重试也不进死信 | 手动 ack 模式下异常不会自动 reject；`simple.retry.*` 也只在 AUTO ack 生效 | 监听器内 catch 后显式 `basicNack(tag, false, false)` |

## 八、待补充（下一批）

Kafka 消费组 lag 监控、ClickHouse 二级索引与 TTL、RabbitMQ 优先级队列与惰性队列、
RocketMQ、Sentinel、Seata、XXL-JOB、Elasticsearch、MongoDB 等。
