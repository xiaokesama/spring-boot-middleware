#!/usr/bin/env bash
# 在 WSL2 (Ubuntu 24.04) 中安装 Kafka（KRaft 单节点，无需 ZooKeeper）
# 可重复执行：已安装时只做配置校正 + 重启
set -euo pipefail

KAFKA_VERSION="3.9.0"
SCALA_VERSION="2.13"
DIST="kafka_${SCALA_VERSION}-${KAFKA_VERSION}"
INSTALL_DIR="/opt/${DIST}"
DATA_DIR="/var/lib/kafka-logs"

# 华为云镜像实测最快（阿里/清华/中科大对 3.9.0 均 404）
MIRRORS=(
  "https://mirrors.huaweicloud.com/apache/kafka/${KAFKA_VERSION}"
  "https://dlcdn.apache.org/kafka/${KAFKA_VERSION}"
  "https://archive.apache.org/dist/kafka/${KAFKA_VERSION}"
)

echo "==> 检查 Java"
java -version 2>&1 | head -1

if [ ! -d "${INSTALL_DIR}" ]; then
  DOWNLOADED=0
  for base in "${MIRRORS[@]}"; do
    url="${base}/${DIST}.tgz"
    echo "==> 尝试下载 ${url}"
    if curl -fsSL --retry 2 --retry-delay 3 --connect-timeout 10 \
         --speed-time 60 --speed-limit 10240 -o /tmp/kafka.tgz "${url}"; then
      size=$(stat -c%s /tmp/kafka.tgz)
      echo "==> 下载完成：${size} bytes"
      if [ "${size}" -gt 100000000 ]; then
        DOWNLOADED=1
        break
      fi
    fi
    echo "==> 该镜像不可用，换下一个"
  done

  if [ "${DOWNLOADED}" -ne 1 ]; then
    echo "!!! 所有镜像均下载失败" >&2
    exit 1
  fi

  tar xzf /tmp/kafka.tgz -C /opt
  rm -f /tmp/kafka.tgz
else
  echo "==> ${INSTALL_DIR} 已存在，跳过下载"
fi

mkdir -p "${DATA_DIR}" /opt/kafka

echo "==> 生成 KRaft 配置 /opt/kafka/server.properties"
cp "${INSTALL_DIR}/config/kraft/server.properties" /opt/kafka/server.properties
sed -i "s|^log.dirs=.*|log.dirs=${DATA_DIR}|"                                             /opt/kafka/server.properties
sed -i 's|^listeners=.*|listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://127.0.0.1:9093|'    /opt/kafka/server.properties
sed -i 's|^num.partitions=.*|num.partitions=3|'                                          /opt/kafka/server.properties
sed -i 's|^default.replication.factor=.*|default.replication.factor=1|'                  /opt/kafka/server.properties
sed -i 's|^offsets.topic.replication.factor=.*|offsets.topic.replication.factor=1|'      /opt/kafka/server.properties
sed -i 's|^transaction.state.log.replication.factor=.*|transaction.state.log.replication.factor=1|' /opt/kafka/server.properties
sed -i 's|^transaction.state.log.min.isr=.*|transaction.state.log.min.isr=1|'            /opt/kafka/server.properties

# advertised.listeners 在默认配置里是注释掉的，Kafka 会从 listeners 推导出 0.0.0.0，
# 而 0.0.0.0 是不可路由的元地址，kafka-storage.sh 会直接报错，所以必须显式补上。
if grep -q '^advertised.listeners=' /opt/kafka/server.properties; then
  sed -i 's|^advertised.listeners=.*|advertised.listeners=PLAINTEXT://localhost:9092|' /opt/kafka/server.properties
else
  echo 'advertised.listeners=PLAINTEXT://localhost:9092' >> /opt/kafka/server.properties
fi

grep -E '^(listeners|advertised.listeners|controller.quorum.voters|log.dirs)=' /opt/kafka/server.properties

echo "==> 格式化存储目录（仅首次）"
if [ ! -f "${DATA_DIR}/meta.properties" ]; then
  rm -rf "${DATA_DIR:?}"/*
  CLUSTER_ID="$("${INSTALL_DIR}/bin/kafka-storage.sh" random-uuid)"
  "${INSTALL_DIR}/bin/kafka-storage.sh" format -t "${CLUSTER_ID}" -c /opt/kafka/server.properties
else
  echo "==> ${DATA_DIR}/meta.properties 已存在，跳过格式化"
fi

echo "==> 注册 systemd 服务"
cat > /etc/systemd/system/kafka.service <<UNIT
[Unit]
Description=Apache Kafka (KRaft single node)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=${INSTALL_DIR}/bin/kafka-server-start.sh /opt/kafka/server.properties
ExecStop=${INSTALL_DIR}/bin/kafka-server-stop.sh
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable kafka
systemctl restart kafka

echo "==> 等待 broker 就绪"
for i in $(seq 1 30); do
  if "${INSTALL_DIR}/bin/kafka-topics.sh" --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
    echo "==> Kafka 已就绪（第 ${i} 次探测）"
    break
  fi
  sleep 2
done

echo "--- 监听端口 ---"
ss -ltn | grep -E ':(9092|9093)' || true
"${INSTALL_DIR}/bin/kafka-topics.sh" --bootstrap-server localhost:9092 --list || true
echo "==> 完成。Windows 侧可访问 localhost:9092"
