# springboot-rabbitmq

RabbitMQ 消息模型练习。端口 **8091**，依赖 RabbitMQ `5672`（WSL2 安装，管理台 15672）。

## 拓扑

```
lab.direct   ──rk=demo──────────┐
lab.topic    ──rk=order.*───────┤
lab.fanout   ──(广播)───────────┼──> lab.queue.demo ──(nack/异常)──> lab.dlx ──> lab.queue.dlq
lab.headers  ──type=demo────────┘
lab.direct   ──rk=delay──> lab.queue.delay ──(TTL 5s 过期)──> lab.dlx? 否，经 lab.direct 回到业务队列
```

## 四种交换机

| 类型 | 路由规则 | 测试用例 |
|---|---|---|
| `direct` | routingKey 完全匹配 | `order.*` 不适用，见下一行 |
| `topic` | `*` 匹配一个词，`#` 匹配多个词 | `order.created` 命中，`order.created.paid` 不命中 |
| `fanout` | 忽略 routingKey，广播 | 直接投递 |
| `headers` | 按 header 匹配（`x-match=all`） | `type=demo` 命中，`type=other` 不命中 |

## 场景

| 场景 | 接口 | 观察点 |
|---|---|---|
| direct | `POST /rabbit/direct?message=hello` | 发布确认 `ack=true`，消息被消费 |
| topic 通配 | `POST /rabbit/topic?routingKey=order.created&message=x` | 命中；换成 `order.created.paid` 不命中 |
| fanout | `POST /rabbit/fanout?message=x` | 广播到绑定队列 |
| headers | `POST /rabbit/headers?message=x&type=demo` | header 不匹配则不投递 |
| 延迟队列 | `POST /rabbit/delay?message=x` | 5 秒后才被消费 |
| 立即死信 | `POST /rabbit/fail?mode=nack&message=x` | `basicNack(requeue=false)` → 进 DLQ |
| 异常后死信 | `POST /rabbit/fail?mode=error&message=x` | 业务抛异常 → 消费者自己 `basicNack` → 进 DLQ |
| 队列深度 | `GET /rabbit/queues` | 消息数、消费者数 |

## 运行

```powershell
mvn -B spring-boot:run -pl springboot-rabbitmq

curl.exe -X POST "http://localhost:8091/rabbit/direct?message=hello"
curl.exe -X POST "http://localhost:8091/rabbit/fail?mode=nack&message=bad"
curl.exe "http://localhost:8091/rabbit/dead-letters"
```

管理台：<http://localhost:15672>（guest/guest），可以直接看到三个队列的消息进出。

## 关键点

1. **ack 与 confirm 是两回事**：`basicAck` 是消费端确认（消息可以删了），
   `publisher-confirm` 是 broker 确认收到（消息已落盘）。两个都要有才完整。
2. **`basicNack(requeue=true)` 容易造成死循环**：消息反复回队列、反复失败。
   正确做法是 `requeue=false` 交给死信队列，或者用重试 + 退避。
3. **手动 ack 下的两个反直觉行为**（都是实测出来的）：
   - 监听器抛异常**不会自动 reject**，消息会一直挂在 unacked 状态，必须自己
     `basicNack(deliveryTag, false, false)` 才会进死信；
   - `spring.rabbitmq.listener.simple.retry.*` 只在 **AUTO ack** 模式下生效，
     manual ack 时那套重试配置形同虚设。要重试就得自己实现（或改用 AUTO ack）。
3. **重试次数用尽后**：`default-requeue-rejected=false` 保证消息进死信而不是无限回队。
4. **延迟队列是"TTL + 死信"拼出来的**：RabbitMQ 没有原生延迟队列。
   注意 TTL 只在「队首」检查，同一队列里 TTL 不同的消息可能延迟不准；
   要精确延迟就装 `rabbitmq_delayed_message_exchange` 插件。
5. **`prefetch` 决定消费能力**：每个消费者最多 N 条未 ack 消息在途，
   太小吞吐上不去，太大内存和重复风险上升。
6. **WSL2 里必须绑 IPv4**：RabbitMQ 默认监听 `[::]:5672`，而 Windows 侧 localhost 转发只认 IPv4，
   表现为"连接被拒"。脚本里已写入 `listeners.tcp.default = 0.0.0.0:5672`。

## 测试

```powershell
mvn -B test -pl springboot-rabbitmq
```

7 个用例，开始前探测 `localhost:5672`，**服务未启动时自动跳过**。
延迟队列用例需要等待 5 秒 TTL，整类约 15 秒。
