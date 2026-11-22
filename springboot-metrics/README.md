# springboot-metrics

Actuator + Micrometer + Prometheus 可观测性练习。端口 **8088**，无外部依赖。

## 四种指标类型

| 类型 | 语义 | 本项目示例 |
|---|---|---|
| `Counter` | 只增不减 | `lab.order.created` / `lab.order.failed` |
| `Gauge` | 当前值，可增可减 | `lab.order.pending` 处理中订单数 |
| `Timer` | 耗时分布 + 次数 | `lab.order.create.latency`（`@Timed`） |
| `DistributionSummary` | 数值分布 | `lab.order.amount` 订单金额 |

## 场景

| 场景 | 接口 | 观察点 |
|---|---|---|
| 下单埋点 | `POST /order?amount=99` | Counter / Timer / Summary 同步累加 |
| 失败计数 | `POST /order?amount=10&fail=true` | `lab.order.failed` +1，`@Timed` 仍记录耗时 |
| Gauge 瞬时值 | `GET /order/meters` | 请求结束后 pending 归 0 |
| 指标清单 | `GET /order/meters` | 列出所有 `lab.*` 指标及其类型、标签 |
| Prometheus 抓取 | `GET /actuator/prometheus` | 文本格式，可直接被 Prometheus 抓取 |
| 健康检查 | `GET /actuator/health` | 展示 details |

## 运行

```powershell
mvn -B spring-boot:run -pl springboot-metrics

curl.exe -X POST "http://localhost:8088/order?amount=199"
curl.exe "http://localhost:8088/order/meters"
curl.exe "http://localhost:8088/actuator/prometheus"
```

## 接 Prometheus + Grafana（可选）

`prometheus.yml` 加一个 job：

```yaml
scrape_configs:
  - job_name: 'springboot-metrics'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8088']
```

Grafana 里常用查询：

```promql
# QPS
rate(lab_order_created_total[1m])
# P95 耗时（依赖 publishPercentileHistogram）
histogram_quantile(0.95, sum(rate(lab_order_create_latency_seconds_bucket[5m])) by (le))
# 失败率
sum(rate(lab_order_failed_total[5m])) / sum(rate(lab_order_create_invocations_total[5m]))
```

## 关键点

1. **`@Timed` 需要 `TimedAspect` Bean**，否则注解不生效（很多人踩过）。
   本模块为了它额外引入了 `spring-boot-starter-aop`。
2. **Gauge 持有的是弱引用**，注册临时对象会导致指标消失；要注册容器里的单例 Bean。
3. **指标名和标签是长期契约**。改名等于重建看板；
   标签基数（如把 userId 当标签）会把 Prometheus 打爆。
4. **percentiles 与 histogram 别混用**：客户端 percentiles 不可聚合，
   多实例场景要用 `publishPercentileHistogram` + `histogram_quantile` 在服务端算。
5. `/actuator/prometheus` 默认不暴露，必须在
   `management.endpoints.web.exposure.include` 里显式加上。

## 测试

```powershell
mvn -B test -pl springboot-metrics
```

4 个用例直接读 `MeterRegistry` 断言计数，不依赖 Web 容器。
