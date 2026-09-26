#!/usr/bin/env bash
# ============================================================
# 报表派单 Agent Demo —— Linux 一键部署脚本
#
#   ./deploy.sh up        构建并启动全部服务（首次用这个）
#   ./deploy.sh down      停止并移除容器（数据卷保留）
#   ./deploy.sh restart   重启所有服务
#   ./deploy.sh rebuild   强制重新构建镜像并启动
#   ./deploy.sh logs      跟踪查看日志
#   ./deploy.sh ps        查看状态
#   ./deploy.sh update    重新构建前后端并更新（数据库不动）
#   ./deploy.sh destroy   停止并删除容器 + 数据卷（会清空 MySQL/Redis 数据）
#   ./deploy.sh start-mock / start-real  切到模拟模型 / 真实模型
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

ENV_FILE="$ROOT_DIR/.env"
COMPOSE="docker compose"

c_info()  { printf '\033[32m[INFO]\033[0m  %s\n' "$*"; }
c_warn()  { printf '\033[33m[WARN]\033[0m  %s\n' "$*"; }
c_error() { printf '\033[31m[ERROR]\033[0m %s\n' "$*" >&2; }

# ---------- 环境检查 ----------
check_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    c_error "未找到 docker，请先安装 Docker Engine：https://docs.docker.com/engine/install/"
    exit 1
  fi
  if ! docker info >/dev/null 2>&1; then
    c_error "Docker 未运行，或当前用户没有操作权限。"
    c_warn  "若是权限问题，执行：sudo usermod -aG docker \$USER && newgrp docker"
    exit 1
  fi
  # 兼容老的 docker-compose v1
  if ! docker compose version >/dev/null 2>&1; then
    if command -v docker-compose >/dev/null 2>&1; then
      COMPOSE="docker-compose"
      c_warn "检测到旧版 docker-compose，建议升级到 Compose V2。"
    else
      c_error "未找到 Docker Compose，请安装 Compose V2 插件。"
      exit 1
    fi
  fi
}

random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 12
  else
    tr -dc 'a-f0-9' < /dev/urandom | head -c 24
  fi
}

# 探测服务器地址：先取公网 IP，失败退回内网 IP，再失败用 _
detect_server_name() {
  local ip=""
  if command -v curl >/dev/null 2>&1; then
    ip="$(curl -fsS --max-time 3 https://api.ipify.org 2>/dev/null || true)"
  fi
  if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
  fi
  echo "${ip:-_}"
}

# ---------- 生成 .env ----------
ensure_env() {
  if [ -f "$ENV_FILE" ]; then
    c_info "沿用已有的 .env 配置。"
    ensure_basic_auth
    return
  fi

  local mysql_pwd redis_pwd web_pwd server_name
  mysql_pwd="$(random_secret)"
  redis_pwd="$(random_secret)"
  web_pwd="$(random_secret)"
  server_name="$(detect_server_name)"

  sed -e "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=${mysql_pwd}|" \
      -e "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=${redis_pwd}|" \
      -e "s|^BASIC_AUTH_PASSWORD=.*|BASIC_AUTH_PASSWORD=${web_pwd}|" \
      -e "s|^SERVER_NAME=.*|SERVER_NAME=${server_name}|" \
      "$ROOT_DIR/.env.example" > "$ENV_FILE"

  chmod 600 "$ENV_FILE" 2>/dev/null || true
  c_info "已按本机情况生成 .env（随机密码，请留存）"
  c_warn "MySQL root 密码：${mysql_pwd}"
  c_warn "Redis 密码：${redis_pwd}"
  c_warn "网页访问口令：$(env_get BASIC_AUTH_USER demo) / ${web_pwd}"
}

# 旧版 .env 没有网页访问口令时补一个随机口令；仍是示例值时拒绝启动，避免带着公开口令上线
ensure_basic_auth() {
  local web_pwd
  web_pwd="$(env_get BASIC_AUTH_PASSWORD)"
  if [ -z "$web_pwd" ]; then
    web_pwd="$(random_secret)"
    set_env BASIC_AUTH_USER "$(env_get BASIC_AUTH_USER demo)"
    set_env BASIC_AUTH_PASSWORD "$web_pwd"
    c_warn "已为 .env 补充网页访问口令：$(env_get BASIC_AUTH_USER demo) / ${web_pwd}"
  elif [ "$web_pwd" = "change-me-web" ]; then
    c_error ".env 里的 BASIC_AUTH_PASSWORD 仍是示例值 change-me-web，请改成自己的口令后再执行。"
    exit 1
  fi
}

compose() {
  # shellcheck disable=SC2086
  $COMPOSE --env-file "$ENV_FILE" "$@"
}

# 从 .env 读取某个键的值（读不到就用默认值）
env_get() {
  local key="$1" default="${2:-}" val
  val="$(grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2- || true)"
  echo "${val:-$default}"
}

# 就地改写 .env 里某个键
set_env() {
  local key="$1" value="$2"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    sed -i.bak -E "s|^${key}=.*|${key}=${value}|" "$ENV_FILE" && rm -f "${ENV_FILE}.bak"
  else
    echo "${key}=${value}" >> "$ENV_FILE"
  fi
}

print_access() {
  local port server
  port="$(env_get HTTP_PORT 80)"
  server="$(env_get SERVER_NAME _)"
  if [ "$server" = "_" ] || [ -z "$server" ]; then
    server="服务器IP"
  fi

  echo
  c_info "部署完成，访问地址："
  if [ "$port" = "80" ]; then
    echo "        http://${server}/"
  else
    echo "        http://${server}:${port}/"
  fi
  echo
  echo "  · 打开页面会先弹出登录框：账号 $(env_get BASIC_AUTH_USER demo)，口令见 .env 的 BASIC_AUTH_PASSWORD"
  echo "  · 演示身份在页面右上角切换：用户1 / 用户2 / 管理员"
  echo "  · 浏览器请用服务器 IP 或域名访问，不要用 localhost"
  echo "  · 云服务器记得放行 ${port} 端口（安全组 / 防火墙）"
  echo
  echo "  常用命令：./deploy.sh logs | ps | down"
  echo
}

# 等四个容器都起来；发现 unhealthy 就直接报错并打印日志
wait_healthy() {
  c_info "等待服务就绪（首次启动 MySQL 初始化约 30~60 秒）..."
  local timeout=240 elapsed=0 unhealthy running
  while [ "$elapsed" -lt "$timeout" ]; do
    unhealthy="$(docker ps --filter "name=report-demo-" --filter "health=unhealthy" -q 2>/dev/null | wc -l | tr -d ' ')"
    if [ "$unhealthy" != "0" ]; then
      c_error "有容器处于 unhealthy 状态，最近日志如下："
      compose logs --tail=40
      exit 1
    fi
    running="$(docker ps --filter "name=report-demo-" --format '{{.Names}}' 2>/dev/null | wc -l | tr -d ' ')"
    if [ "$running" -ge 4 ]; then
      c_info "4 个容器均已启动。"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  c_warn "等待超时，请用 ./deploy.sh ps 与 ./deploy.sh logs 排查。"
}

# ---------- 各动作 ----------
action_up() {
  check_docker
  ensure_env
  c_info "开始构建镜像并启动服务（首次构建要下载依赖，请耐心等待）..."
  compose up -d --build
  wait_healthy
  print_access
}

action_rebuild() {
  check_docker
  ensure_env
  c_info "不使用缓存，强制重新构建镜像..."
  compose build --no-cache
  compose up -d --force-recreate
  wait_healthy
  print_access
}

action_update() {
  check_docker
  ensure_env
  c_info "重新构建前后端镜像并更新（MySQL / Redis 不动）..."
  compose build backend frontend
  compose up -d --no-deps backend frontend
  c_info "更新完成。"
}

action_down() {
  check_docker
  ensure_env
  c_info "停止并移除容器（MySQL / Redis 数据卷保留）..."
  compose down --remove-orphans
  c_info "已停止，数据仍在，下次 up 可恢复。"
}

action_destroy() {
  check_docker
  ensure_env
  c_warn "此操作会删除 MySQL 与 Redis 数据卷：历史会话、审计记录、已发布规则将全部丢失。"
  read -r -p "确认继续请输入 yes： " ans
  if [ "$ans" != "yes" ]; then
    c_info "已取消。"
    exit 0
  fi
  compose down -v --remove-orphans
  c_info "已彻底清除。"
}

action_restart() {
  check_docker
  ensure_env
  compose restart
  print_access
}

action_logs() {
  check_docker
  ensure_env
  compose logs -f --tail=200
}

action_ps() {
  check_docker
  ensure_env
  compose ps
}

action_start_mock() {
  check_docker
  ensure_env
  set_env SPRING_ARGS "--spring.profiles.active=mock"
  compose up -d --force-recreate backend
  c_info "已切到模拟模型（无需 API Key）。"
}

action_start_real() {
  check_docker
  ensure_env
  local key base model
  key="$(env_get LLM_API_KEY)"
  if [ -z "$key" ] || [ "$key" = "sk-no-key-set" ]; then
    c_error "LLM_API_KEY 还没填，请先编辑 .env 配置真实模型。"
    exit 1
  fi
  base="$(env_get LLM_BASE_URL https://dashscope.aliyuncs.com/compatible-mode)"
  model="$(env_get LLM_MODEL qwen3.7-plus)"
  set_env SPRING_ARGS ""
  set_env LLM_BASE_URL "$base"
  set_env LLM_MODEL "$model"
  compose up -d --force-recreate backend
  c_info "已切到真实模型：${model} @ ${base}"
}

usage() {
  sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'
}

case "${1:-up}" in
  up)             action_up ;;
  rebuild)        action_rebuild ;;
  update)         action_update ;;
  down)           action_down ;;
  destroy)        action_destroy ;;
  restart)        action_restart ;;
  logs)           action_logs ;;
  ps|status)      action_ps ;;
  start-mock)     action_start_mock ;;
  start-real)     action_start_real ;;
  help|-h|--help) usage ;;
  *)              c_error "未知命令：$1"; usage; exit 1 ;;
esac
