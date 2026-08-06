#!/bin/bash
# ============================================================
# AI Interview Platform - 腾讯云服务器一键部署脚本
# 用法：在本地 Git Bash 中执行
#   bash scripts/deploy-server.sh
# ============================================================
set -euo pipefail

SERVER="ubuntu@106.52.54.72"
PROJECT_DIR="/opt/interview-guide"

echo "=========================================="
echo " AI Interview Platform - 服务器部署"
echo " 目标: $SERVER"
echo "=========================================="
echo ""

# ---- Step 1: 测试 SSH 连接 ----
echo "[1/6] 测试 SSH 连接..."
if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "$SERVER" "echo SSH连接成功"; then
  echo "  尝试密码登录..."
  ssh -o ConnectTimeout=10 "$SERVER" "echo SSH连接成功" || {
    echo "❌ SSH 连接失败，请检查服务器状态和网络"
    exit 1
  }
fi
echo "  ✅ SSH 连接正常"
echo ""

# ---- Step 2: 上传项目文件 ----
echo "[2/6] 上传部署文件到服务器..."
ssh "$SERVER" "sudo mkdir -p $PROJECT_DIR && sudo chown ubuntu:ubuntu $PROJECT_DIR"
scp docker-compose.prod.yml "$SERVER:$PROJECT_DIR/"
scp docker/postgres/init.sql "$SERVER:$PROJECT_DIR/docker/postgres/init.sql" 2>/dev/null || {
  ssh "$SERVER" "mkdir -p $PROJECT_DIR/docker/postgres"
  scp docker/postgres/init.sql "$SERVER:$PROJECT_DIR/docker/postgres/init.sql"
}
echo "  ✅ 文件上传完成"
echo ""

# ---- Step 3: 创建 .env 配置 ----
echo "[3/6] 配置环境变量..."
echo "  请输入以下配置（直接回车使用默认值）："
echo ""

read -r -p "  AI_BAILIAN_API_KEY (阿里云百炼API Key，必填): " BAILIAN_KEY
while [ -z "$BAILIAN_KEY" ]; do
  read -r -p "  ⚠️  此项为必填，请输入 AI_BAILIAN_API_KEY: " BAILIAN_KEY
done

read -r -p "  APP_AI_CONFIG_ENCRYPTION_KEY (加密密钥，回车自动生成): " ENCRYPT_KEY
if [ -z "$ENCRYPT_KEY" ]; then
  ENCRYPT_KEY=$(openssl rand -base64 32 2>/dev/null || cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 32)
  echo "  已自动生成: $ENCRYPT_KEY"
fi

read -r -p "  POSTGRES_PASSWORD (数据库密码，回车自动生成): " DB_PASSWORD
if [ -z "$DB_PASSWORD" ]; then
  DB_PASSWORD=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 16)
  echo "  已自动生成: $DB_PASSWORD"
fi

read -r -p "  APP_STORAGE_SECRET_KEY (MinIO存储密码，回车自动生成): " MINIO_KEY
if [ -z "$MINIO_KEY" ]; then
  MINIO_KEY=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 16)
  echo "  已自动生成: $MINIO_KEY"
fi

# 获取服务器公网 IP
SERVER_IP="106.52.54.72"

# 生成 .env 文件
cat > /tmp/interview-guide.env << EOF
# === AI 服务密钥（必填）===
AI_BAILIAN_API_KEY=$BAILIAN_KEY
APP_AI_CONFIG_ENCRYPTION_KEY=$ENCRYPT_KEY

# === 数据库 ===
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=interview_guide
POSTGRES_USER=postgres
POSTGRES_PASSWORD=$DB_PASSWORD

# === Redis ===
REDIS_HOST=redis
REDIS_PORT=6379

# === MinIO 对象存储 ===
APP_STORAGE_ENDPOINT=http://minio:9000
APP_STORAGE_ACCESS_KEY=minioadmin
APP_STORAGE_SECRET_KEY=$MINIO_KEY
APP_STORAGE_BUCKET=interview-guide
APP_STORAGE_REGION=us-east-1

# === CORS 允许的前端地址 ===
CORS_ALLOWED_ORIGINS=http://$SERVER_IP,http://localhost:80

# === AI 模型配置 ===
AI_MODEL=qwen3.5-flash
EOF

scp /tmp/interview-guide.env "$SERVER:$PROJECT_DIR/.env"
rm -f /tmp/interview-guide.env
echo "  ✅ .env 配置完成"
echo ""

# ---- Step 4: 安装 Docker ----
echo "[4/6] 检查/安装 Docker 环境..."
ssh "$SERVER" bash -s << 'DOCKER_SETUP'
set -euo pipefail

# 检查 Docker 是否已安装
if command -v docker &>/dev/null && docker compose version &>/dev/null 2>&1; then
  echo "  ✅ Docker 和 Docker Compose 已安装"
  docker --version
  exit 0
fi

echo "  正在安装 Docker..."
# 使用腾讯云镜像加速（国内更快）
curl -fsSL https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg 2>/dev/null || \
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu $(lsb_release -cs) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null 2>&1 || \
  curl -fsSL https://get.docker.com | sudo bash

sudo apt-get update -qq
sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 将 ubuntu 用户加入 docker 组
sudo usermod -aG docker ubuntu

echo "  ✅ Docker 安装完成"
docker --version
DOCKER_SETUP
echo ""

# ---- Step 5: 配置 Swap（2GB） ----
echo "[5/6] 配置 Swap 内存..."
ssh "$SERVER" bash -s << 'SWAP_SETUP'
set -euo pipefail
if swapon --show | grep -q swap; then
  echo "  ✅ Swap 已存在"
  free -h | grep Swap
else
  echo "  创建 2GB Swap 文件..."
  sudo fallocate -l 2G /swapfile 2>/dev/null || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  if ! grep -q swapfile /etc/fstab; then
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  fi
  echo "  ✅ Swap 配置完成"
  free -h | grep Swap
fi
SWAP_SETUP
echo ""

# ---- Step 6: 启动服务 ----
echo "[6/6] 拉取镜像并启动服务..."
echo ""
echo "  ⚠️  注意：首次启动需要从 GitHub Container Registry 拉取镜像。"
echo "  如果镜像还未构建（首次 GitHub Actions 运行前），需要先推送代码触发 CI 构建。"
echo ""
read -r -p "  是否现在尝试启动服务？(y/n): " START_NOW

if [ "$START_NOW" = "y" ] || [ "$START_NOW" = "Y" ]; then
  ssh "$SERVER" bash -s << 'START_SERVICE'
set -euo pipefail
cd /opt/interview-guide

# 首次需要先登出再登入使 docker 组生效，这里用 sudo
echo "  拉取镜像..."
sudo docker compose -f docker-compose.prod.yml pull 2>&1 || echo "  ⚠️  镜像拉取失败（可能还未构建），请先推送代码到 GitHub 触发 CI"

echo "  启动服务..."
sudo docker compose -f docker-compose.prod.yml up -d 2>&1 || echo "  ⚠️  启动失败，请检查日志"

echo ""
echo "  检查容器状态..."
sudo docker compose -f docker-compose.prod.yml ps
START_SERVICE
fi

echo ""
echo "=========================================="
echo " 🎉 服务器部署脚本执行完毕！"
echo "=========================================="
echo ""
echo " 后续步骤："
echo " 1. 配置 GitHub Secrets（在仓库 Settings → Secrets and variables → Actions）："
echo "    - SSH_HOST: 106.52.54.72"
echo "    - SSH_USERNAME: ubuntu"
echo "    - SSH_PRIVATE_KEY: $(cat ~/.ssh/id_ed25519 2>/dev/null | head -1)... (你的 SSH 私钥内容)"
echo ""
echo " 2. 推送代码到 GitHub master 分支触发 CI/CD 自动构建"
echo ""
echo " 3. 构建完成后，SSH 到服务器手动启动："
echo "    ssh $SERVER"
echo "    cd /opt/interview-guide"
echo "    docker compose -f docker-compose.prod.yml pull"
echo "    docker compose -f docker-compose.prod.yml up -d"
echo ""
echo " 4. 浏览器访问: http://$SERVER_IP"
echo ""
