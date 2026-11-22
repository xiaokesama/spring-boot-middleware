#!/usr/bin/env bash
# 查看 WSL2 中三个中间件的状态与端口
set -uo pipefail

echo "===== systemd 状态 ====="
for svc in kafka clickhouse-server rabbitmq-server; do
  printf '%-18s %s\n' "$svc" "$(systemctl is-active "$svc" 2>/dev/null || echo unknown)"
done

echo
echo "===== 监听端口 ====="
ss -ltn | grep -E ':(9092|8123|9000|5672|15672)' || echo "（无）"

echo
echo "===== 连通性自检 ====="
if clickhouse-client --query "SELECT 'clickhouse ok', version()" 2>/dev/null; then :; else echo "clickhouse: FAIL"; fi
if rabbitmqctl status >/dev/null 2>&1; then echo "rabbitmq ok, version $(rabbitmqctl version 2>/dev/null)"; else echo "rabbitmq: FAIL"; fi
if /opt/kafka_2.13-3.9.0/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
  echo "kafka ok, topics:"; /opt/kafka_2.13-3.9.0/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
else
  echo "kafka: FAIL"
fi
