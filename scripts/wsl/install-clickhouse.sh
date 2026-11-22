#!/usr/bin/env bash
# 在 WSL2 (Ubuntu 24.04) 中安装 ClickHouse Server + Client
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

echo "==> 准备 apt 源"
apt-get update -qq
apt-get install -y -qq apt-transport-https ca-certificates curl gnupg

mkdir -p /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/clickhouse-keyring.gpg ]; then
  curl -fsSL https://packages.clickhouse.com/rpm/lts/repodata/repomd.xml.key \
    | gpg --dearmor -o /etc/apt/keyrings/clickhouse-keyring.gpg
fi

echo "deb [signed-by=/etc/apt/keyrings/clickhouse-keyring.gpg] https://packages.clickhouse.com/deb stable main" \
  > /etc/apt/sources.list.d/clickhouse.list

apt-get update -qq

echo "==> 安装 clickhouse-server / clickhouse-client"
apt-get install -y -qq clickhouse-server clickhouse-client

# 本地学习环境：去掉安装时随机生成的 default 用户密码，恢复免密
if [ -f /etc/clickhouse-server/users.d/default-password.xml ]; then
  echo "==> 移除随机密码配置 users.d/default-password.xml"
  rm -f /etc/clickhouse-server/users.d/default-password.xml
fi

# 允许 Windows 侧通过 WSL 端口转发访问（仅监听 0.0.0.0）
if grep -q '<!-- <listen_host>::</listen_host> -->' /etc/clickhouse-server/config.xml; then
  sed -i 's|<!-- <listen_host>::</listen_host> -->|<listen_host>0.0.0.0</listen_host>|' \
    /etc/clickhouse-server/config.xml
elif ! grep -q '<listen_host>0.0.0.0</listen_host>' /etc/clickhouse-server/config.xml; then
  sed -i '0,/<listen_host>127.0.0.1<\/listen_host>/s||<listen_host>0.0.0.0</listen_host>|' \
    /etc/clickhouse-server/config.xml
fi

systemctl daemon-reload
systemctl enable clickhouse-server
systemctl restart clickhouse-server

echo "==> 等待 ClickHouse 就绪"
for i in $(seq 1 30); do
  if clickhouse-client --query "SELECT 1" >/dev/null 2>&1; then
    echo "==> ClickHouse 已就绪（第 ${i} 次探测）"
    break
  fi
  sleep 1
done

clickhouse-client --query "SELECT version(), currentUser()"
echo "==> 完成。HTTP 端口 8123，Native 端口 9000"
