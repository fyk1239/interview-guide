#!/bin/bash
# SSH 重试包装：应对服务器连接不稳定（banner 阶段挂起时 ConnectTimeout 不生效，用 timeout 强制限时）
# 用法: scripts/ssh-retry.sh "<远程命令>"
#   scripts/ssh-retry.sh "docker --version"
set -uo pipefail

SERVER="ubuntu@106.52.54.72"
CMD="${1:-echo ok}"
MAX_ATTEMPTS="${2:-8}"

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  if timeout 30 ssh -o ConnectTimeout=20 -o ServerAliveInterval=8 -o ServerAliveCountMax=2 "$SERVER" "$CMD"; then
    exit 0
  fi
  echo "--- 第 $i/$MAX_ATTEMPTS 次连接失败，3 秒后重试 ---" >&2
  sleep 3
done

echo "❌ 连续 $MAX_ATTEMPTS 次连接失败，放弃" >&2
exit 1
