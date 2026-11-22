#!/usr/bin/env bash
# 在 WSL2 (Ubuntu 24.04) 中安装 RabbitMQ（含 Web 管理台）
# 可重复执行：已安装时只做配置校正 + 重启
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

if ! command -v rabbitmqctl >/dev/null 2>&1; then
  echo "==> 安装 rabbitmq-server"
  apt-get update -qq
  apt-get install -y -qq rabbitmq-server
fi

echo "==> 写配置"
mkdir -p /etc/rabbitmq/conf.d

# 1) 允许 guest 从非 loopback 登录（仅本地学习环境，生产禁用）
cat > /etc/rabbitmq/conf.d/10-guest.conf <<'CONF'
loopback_users.guest = false
CONF

# 2) 显式绑定 IPv4：WSL2 的 localhost 端口转发只认 IPv4，
#    默认的 [::]:5672 在 Windows 侧会表现为连接被拒
cat > /etc/rabbitmq/conf.d/20-listen.conf <<'CONF'
listeners.tcp.default = 0.0.0.0:5672
CONF

systemctl daemon-reload
systemctl enable rabbitmq-server

echo "==> 启用管理插件（15672），离线写入后由下面的 restart 生效"
rabbitmq-plugins enable --offline rabbitmq_management >/dev/null

echo "==> 重启使配置与插件生效"
systemctl restart rabbitmq-server

echo "==> 等待就绪"
for i in $(seq 1 40); do
  if rabbitmqctl status >/dev/null 2>&1; then
    echo "==> RabbitMQ 已就绪（第 ${i} 次探测）"
    break
  fi
  sleep 1
done

rabbitmqctl version
rabbitmqctl list_users
echo "--- 监听端口 ---"
ss -ltn | grep -E ':(5672|15672)' || true
echo "==> 完成。AMQP 5672，管理台 http://localhost:15672 (guest/guest)"
