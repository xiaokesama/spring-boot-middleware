# springboot-kafka

Kafka 生产消费练习。端口 **8089**，依赖 Kafka `9092`（WSL2 安装的 KRaft 单节点）。

## 五个场景

| 场景 | 接口 | 观察点 |
|---|---|---|
| 生产 + 消费 | `POST /kafka/send?key=k1&message=hello` | 返回实际写入的分区与 offset |
| 分区与有序 | 连续两次同 key 发送 | 分区相同 → 单 key 有序 |
| 手动 ack | 见 `ConsumerService` | 处理成功才 `acknowledge()`，至少一次语义 |
| 重试与死信 | `POST /kafka/send?key=x&message=poison:bad` | 不可重试异常直接进 `demo-topic.DLT` |
| 可重试失败 | `POST /kafka/send?key=x&message=retry:down` | 重试 2 次后进 DLT |
| 事务 | `POST /kafka/send-tx?messages=a,b` | 一个事务内多条消息全可见或全不可见 |
| 分区分布 | `GET /kafka/partitions` | 列出分区、leader、副本数 |
| 消费结果 | `GET /kafka/messages`、`GET /kafka/dead-letters` | 内存里保留最近 200 条 |

## 运行

先确保 Kafka 已启动（`scripts/wsl/status.sh` 可查），然后：

```powershell
mvn -B spring-boot:run -pl springboot-kafka

curl.exe -X POST "http://localhost:8089/kafka/send?key=order-1&message=hello"
curl.exe "http://localhost:8089/kafka/messages"
curl.exe -X POST "http://localhost:8089/kafka/send?key=order-2&message=poison:bad"
curl.exe "http://localhost:8089/kafka/dead-letters"
```

## 关键点

1. **分区规则**：显式 key → `murmur2(key) % partitions`，同 key 永远同分区；
   不指定 key → 粘性分区（一段时间固定一个分区，凑批效率高，但无顺序保证）。
2. **分区数决定消费并行度上限**：本模块 3 分区 + `concurrency=3`，
   同一个消费组最多 3 个线程并行；第 4 个消费者会分不到分区而空闲。
3. **关闭自动提交才有"至少一次"**：`enable-auto-commit=false` + 手动 ack。
   代价是可能重复消费（ack 前崩溃），所以消费逻辑要幂等。
4. **重试与死信**：`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`。
   `addNotRetryableExceptions(IllegalArgumentException.class)` 让参数错误不空转重试。
5. **事务消息需要 `read_committed`**：消费端不设置这个隔离级别，会看到被回滚的消息。
6. **`transaction-id-prefix` 是开关**：配置了它 `KafkaTemplate` 才具备事务能力。
   生产多实例时前缀要带实例标识（如 `tx-${HOSTNAME}-`），否则事务 ID 冲突。
7. 死信 topic 的分区数要与原 topic 对齐，否则 `DeadLetterPublishingRecoverer` 投递会失败。

## 测试（不依赖本机 broker）

```powershell
mvn -B test -pl springboot-kafka
```

`@EmbeddedKafka` 起一个进程内 broker，5 个用例覆盖：正常消费、同 key 同分区、
毒消息进 DLT、可重试消息进 DLT、事务全量投递。CI 里没有 Kafka 也能跑。
