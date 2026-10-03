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
#   ./deploy.sh start-real  应用真实模型双服务配置
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
    openssl rand -hex 32
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
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
  umask 077
  if [ -f "$ENV_FILE" ]; then
    c_info "沿用已有的 .env 配置。"
    ensure_service_credentials
    return
  fi

  local mysql_pwd redis_pwd server_name
  mysql_pwd="$(random_secret)"
  redis_pwd="$(random_secret)"
  server_name="$(detect_server_name)"

  sed -e "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=${mysql_pwd}|" \
      -e "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=${redis_pwd}|" \
      -e "s|^SERVER_NAME=.*|SERVER_NAME=${server_name}|" \
      "$ROOT_DIR/.env.example" > "$ENV_FILE"

  chmod 600 "$ENV_FILE" 2>/dev/null || true
  c_info "已按本机情况生成 .env（随机密码，请留存）"
  ensure_service_credentials
  c_info "凭据只保存在权限受限的 .env 文件中。"
}

ensure_service_credentials() {
  local key
  for key in BUSINESS_SERVICE_TOKEN AUTH_BOOTSTRAP_PASSWORD; do
    if [ -z "$(env_get "$key")" ]; then set_env "$key" "$(random_secret)"; fi
  done
  chmod 600 "$ENV_FILE"
}

validate_model() {
  local key model
  key="${LLM_API_KEY:-$(env_get LLM_API_KEY)}"
  model="${LLM_MODEL:-$(env_get LLM_MODEL)}"
  if [ -z "$key" ] || [ "$key" = sk-no-key-set ] || [ -z "$model" ]; then
    c_error "请在环境变量或 .env 配置真实 LLM_API_KEY 和已验证的 LLM_MODEL，再重新启动。"
    return 1
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
  local key="$1" value="$2" temporary
  [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ && "$value" != *$'\n'* && "$value" != *$'\r'* ]] || return 1
  temporary="$(mktemp "${ENV_FILE}.XXXXXX")"
  chmod 600 "$temporary"
  DEPLOY_ENV_KEY="$key" DEPLOY_ENV_VALUE="$value" awk '
    BEGIN { key=ENVIRON["DEPLOY_ENV_KEY"]; value=ENVIRON["DEPLOY_ENV_VALUE"] }
    index($0,key "=")==1 { if(!found) print key "=" value; found=1; next }
    {print}
    END {if(!found) print key "=" value}
  ' "$ENV_FILE" > "$temporary"
  mv -- "$temporary" "$ENV_FILE"
}

print_access() {
  local port server
  port="$(env_get HTTP_PORT 8080)"
  server="$(env_get SERVER_NAME _)"
  if [ "$server" = "_" ] || [ -z "$server" ]; then
    server="$(env_get HTTP_BIND_ADDRESS 127.0.0.1)"
  fi

  echo
  c_info "部署完成，访问地址："
  if [ "$port" = "80" ]; then
    echo "        http://${server}/"
  else
    echo "        http://${server}:${port}/"
  fi
  echo
  echo "  · 应用账号：admin；初始密码见 .env 的 AUTH_BOOTSTRAP_PASSWORD（仅首次空库创建生效）"
  echo "  · 默认仅绑定本机；跨主机访问需要配置 HTTPS 入口和实际监听地址。"
  echo "  · 就绪检查验证数据库、Redis、认证 MCP 协议；真实模型输出需另行验收。"
  echo
  echo "  常用命令：./deploy.sh logs | ps | down"
  echo
}

# 仅检查当前 Compose 项目的五项服务；进程 running 不等于依赖已就绪。
wait_healthy() {
  c_info "等待服务就绪（首次启动 MySQL 初始化约 30~60 秒）..."
  local timeout=${DEPLOY_HEALTH_TIMEOUT_SECONDS:-240} elapsed=0 service id state ready
  local services=("$@")
  if [ "${#services[@]}" -eq 0 ]; then services=(mysql redis business-service backend frontend); fi
  if [[ ! "$timeout" =~ ^[1-9][0-9]*$ ]]; then
    c_error "DEPLOY_HEALTH_TIMEOUT_SECONDS 必须是正整数。"
    return 1
  fi
  while [ "$elapsed" -lt "$timeout" ]; do
    ready=true
    for service in "${services[@]}"; do
      if ! id="$(compose ps --all --quiet "$service")"; then
        c_error "无法查询 ${service} 容器状态。"
        return 1
      fi
      if [ -z "$id" ]; then
        ready=false
        continue
      fi
      if ! state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$id")"; then
        c_error "无法检查 ${service} 容器状态。"
        return 1
      fi
      case "$state" in
        'running healthy') ;;
        *' unhealthy'|exited\ *|dead\ *|removing\ *|*' missing')
          c_error "${service} 未就绪：${state}。"
          compose logs --tail=40 "$service" || true
          return 1
          ;;
        *) ready=false ;;
      esac
    done
    if [ "$ready" = true ]; then
      c_info "指定服务已通过健康检查：${services[*]}。"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  c_error "等待服务健康超时，请用 ./deploy.sh ps 与 ./deploy.sh logs 排查。"
  compose ps || true
  return 1
}

# ---------- 各动作 ----------
action_up() {
  check_docker
  ensure_env
  validate_model
  c_info "开始构建镜像并启动服务（首次构建要下载依赖，请耐心等待）..."
  compose up -d --build
  compose restart frontend
  wait_healthy
  print_access
}

action_rebuild() {
  check_docker
  ensure_env
  validate_model
  c_info "不使用缓存，强制重新构建镜像..."
  compose build --no-cache
  compose up -d --force-recreate
  wait_healthy
  print_access
}

action_update() {
  check_docker
  ensure_env
  validate_model
  c_info "重新构建前后端镜像并更新（MySQL / Redis 不动）..."
  compose build business-service backend frontend
  compose up -d --no-deps --force-recreate business-service
  wait_healthy business-service
  compose up -d --no-deps --force-recreate backend
  wait_healthy backend
  compose up -d --no-deps --force-recreate frontend
  wait_healthy
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
  wait_healthy
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

action_start_real() {
  check_docker
  ensure_env
  validate_model
  compose up -d --no-deps --force-recreate business-service
  wait_healthy business-service
  compose up -d --no-deps --force-recreate backend
  compose restart frontend
  wait_healthy
  c_info "真实模型双服务配置已启动；模型输出请通过实际对话验收。"
}

usage() {
  sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
case "${1:-up}" in
  up)             action_up ;;
  rebuild)        action_rebuild ;;
  update)         action_update ;;
  down)           action_down ;;
  destroy)        action_destroy ;;
  restart)        action_restart ;;
  logs)           action_logs ;;
  ps|status)      action_ps ;;
  start-real)     action_start_real ;;
  help|-h|--help) usage ;;
  *)              c_error "未知命令：$1"; usage; exit 1 ;;
esac
fi
