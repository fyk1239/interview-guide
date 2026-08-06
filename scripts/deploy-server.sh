#!/bin/bash
# ============================================================
# AI Interview Platform - 腾讯云服务器一键部署
# 在你的本地终端中执行：
#   bash scripts/deploy-server.sh
# 前提：项目根目录下有配置好的 .env 文件
# ============================================================
set -euo pipefail

SERVER="ubuntu@106.52.54.72"
REPO="https://github.com/fyk1239/interview-guide.git"
PROJECT_DIR="/opt/interview-guide"

# 使用部署专用密钥 + BatchMode（避免弹出密码/凭据窗口）
SSH="ssh -i ~/.ssh/deploy-keys/interview-guide-deploy -o BatchMode=yes -o ConnectTimeout=25"
REMOTE() { $SSH "$SERVER" bash -s; }

# ---------- 从本地 .env 读取密钥 ----------
if [ ! -f .env ]; then
  echo "❌ 根目录下找不到 .env 文件，请先执行: cp .env.example .env  并填入 API 密钥"
  exit 1
fi

source_env() { grep "^${1}=" .env | head -1 | cut -d= -f2-; }
BAILIAN_KEY=$(source_env AI_BAILIAN_API_KEY)
ENCRYPT_KEY=$(source_env APP_AI_CONFIG_ENCRYPTION_KEY)
DS_KEY=$(source_env PROVIDER_DEEPSEEK_API_KEY)
DS_MODEL=$(source_env PROVIDER_DEEPSEEK_MODEL)

if [ -z "$BAILIAN_KEY" ] || [ "$BAILIAN_KEY" = "your_dashscope_api_key_here" ]; then
  echo "❌ AI_BAILIAN_API_KEY 未配置，请在 .env 中填入阿里云百炼 API Key"
  exit 1
fi

# ---------- 自动生成随机密码（不来自 .env）----------
DB_PW=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 20)
MINIO_PW=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 20)

echo "=========================================="
echo " AI Interview Platform - 一键部署"
echo " 服务器: $SERVER"
echo "=========================================="

# ============================================================
# Step 1: 克隆/更新项目
# ============================================================
echo ""
echo "[1/4] 部署项目文件..."
$SSH "$SERVER" bash -s << REMOTE_SCRIPT
set -euo pipefail
if [ -d "$PROJECT_DIR/.git" ]; then
  echo "  项目已存在，git pull 更新..."
  cd "$PROJECT_DIR" && git pull origin master
else
  echo "  首次部署，git clone..."
  sudo rm -rf "$PROJECT_DIR" 2>/dev/null || true
  git clone "$REPO" "$PROJECT_DIR"
fi
echo "  ✅ 项目文件就绪"
REMOTE_SCRIPT

# ============================================================
# Step 2: 创建 .env（密钥从本地 .env 读取后上传）
# ============================================================
echo ""
echo "[2/4] 生成生产环境 .env..."

$SSH "$SERVER" bash -s << REMOTE_ENV
set -euo pipefail
cat > "$PROJECT_DIR/.env" << EOF
# === AI 服务密钥（必填）===
AI_BAILIAN_API_KEY=$BAILIAN_KEY
APP_AI_CONFIG_ENCRYPTION_KEY=$ENCRYPT_KEY

# === 可选 LLM Provider ===
PROVIDER_DEEPSEEK_API_KEY=$DS_KEY
PROVIDER_DEEPSEEK_MODEL=$DS_MODEL

# === 数据库（Docker 内部 DNS）===
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=interview_guide
POSTGRES_USER=postgres
POSTGRES_PASSWORD=$DB_PW

# === Redis ===
REDIS_HOST=redis
REDIS_PORT=6379

# === MinIO 对象存储 ===
APP_STORAGE_ENDPOINT=http://minio:9000
APP_STORAGE_ACCESS_KEY=minioadmin
APP_STORAGE_SECRET_KEY=$MINIO_PW
APP_STORAGE_BUCKET=interview-guide
APP_STORAGE_REGION=us-east-1

# === CORS ===
CORS_ALLOWED_ORIGINS=http://106.52.54.72,http://localhost:80

# === AI 模型 ===
AI_MODEL=qwen3.5-flash
EOF
echo "  ✅ .env 已生成"
REMOTE_ENV

# ============================================================
# Step 3: 安装 Docker
# ============================================================
echo ""
echo "[3/4] 安装 Docker（首次约 2-3 分钟）..."

$SSH "$SERVER" bash -s << 'REMOTE_DOCKER'
set -euo pipefail
if command -v docker &>/dev/null && docker compose version &>/dev/null 2>&1; then
  echo "  ✅ Docker 已安装"
  docker --version
  exit 0
fi
echo "  正在安装 Docker..."
curl -fsSL https://get.docker.com | sudo bash
sudo usermod -aG docker ubuntu

# 配置腾讯云 registry 镜像加速（国内访问 Docker Hub 不稳定）
echo "  配置镜像加速..."
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json > /dev/null << 'DAEMON_EOF'
{
  "registry-mirrors": ["https://mirrors.tencentyun.com"]
}
DAEMON_EOF
sudo systemctl restart docker

echo "  ✅ Docker 安装完成"
docker --version
docker info 2>/dev/null | grep -A3 "Registry Mirrors" || true
REMOTE_DOCKER

# ============================================================
# Step 4: 启动服务
# ============================================================
echo ""
echo "[4/4] 启动服务..."

$SSH "$SERVER" bash -s << 'REMOTE_START'
set -euo pipefail
cd /opt/interview-guide

echo "  拉取镜像..."
sudo docker compose -f docker-compose.prod.yml pull 2>&1 || {
  echo "  ⚠️ 镜像拉取失败（CI 可能还未构建完成）"
  echo "  CI 构建完成后手动执行:"
  echo "    cd /opt/interview-guide && sudo docker compose -f docker-compose.prod.yml up -d"
  exit 0
}

echo "  启动容器..."
sudo docker compose -f docker-compose.prod.yml up -d --remove-orphans

echo ""
echo "  容器状态:"
sudo docker compose -f docker-compose.prod.yml ps
echo ""
echo "  资源使用:"
free -h | head -2
REMOTE_START

echo ""
echo "=========================================="
echo " 🎉 部署完成"
echo "=========================================="
echo ""
echo " 访问地址: http://106.52.54.72"
echo ""
echo " 常用命令:"
echo "  ssh $SERVER"
echo "  cd /opt/interview-guide"
echo "  sudo docker compose -f docker-compose.prod.yml ps"
echo "  sudo docker compose -f docker-compose.prod.yml logs -f app"
echo ""
