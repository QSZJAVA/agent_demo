# Docker 部署指南

在一台 Linux 服务器上用 Docker 拉起整套 Demo：MySQL 8 + Redis 7 + Spring Boot 后端 + Nginx 前端。
浏览器只访问一个端口，`/api` 由 Nginx 反代到后端，SSE 流式对话已做好不缓冲处理。

## 一、服务器要求

| 项目 | 要求 |
| --- | --- |
| 系统 | CentOS 7+ / Ubuntu 20.04+ / Debian 11+ 等主流发行版 |
| 内存 | 建议 4G 以上；2G 也能跑，需把 `.env` 里 `JAVA_OPTS` 的 `-Xmx` 调到 512m |
| 磁盘 | 10G 以上（镜像约 2G，MySQL 数据卷随会话记录增长） |
| 软件 | Docker Engine 20.10+ 与 Docker Compose V2 |

安装 Docker（官方一键脚本，国内服务器建议换镜像源）：

```bash
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
sudo systemctl enable --now docker
# 让当前用户免 sudo 使用 docker，重新登录后生效
sudo usermod -aG docker $USER
```

验证：`docker compose version` 能输出版本号即可。

## 二、上传项目

在本地项目根目录打包（`.gitignore` / `.dockerignore` 已排除 `node_modules`、`target`、`.env`，包会很小）：

```bash
tar --exclude=node_modules --exclude=target --exclude=dist --exclude=.git -czf demo.tar.gz .
```

上传并解压到服务器：

```bash
scp demo.tar.gz root@<服务器IP>:/opt/
ssh root@<服务器IP>
cd /opt && mkdir -p demo && tar -xzf demo.tar.gz -C demo && cd demo
```

> 也可以直接 `git clone` 到服务器，效果一样。

## 三、一键启动

```bash
chmod +x deploy.sh
./deploy.sh up
```

脚本会自动完成：

1. 检查 Docker / Compose 是否可用
2. 从 `.env.example` 生成 `.env`，写入**随机 MySQL / Redis 密码**，并把 `SERVER_NAME` 自动设为服务器公网 IP
3. 构建后端与前端镜像，拉起 MySQL、Redis，等它们 healthy 后再启动后端
4. 等四个容器全部就绪，打印访问地址

首次执行要下载 MySQL 镜像、Maven 依赖、npm 依赖，视网络情况约 5~15 分钟。脚本最后会打印出**随机生成的 MySQL 与 Redis 密码**，请记录下来（也在 `.env` 里）。

启动完成后访问 `http://<服务器IP>/`。

**云服务器记得放行安全组端口**（默认 80）。80 被占用时改 `.env` 里的 `HTTP_PORT=8000` 再 `./deploy.sh up`。

## 四、模型配置（可选）

默认走 `mock` profile，用关键词模拟模型驱动同一套工具与流程，**不需要 API Key**，适合先验证部署是否成功。

接真实大模型：编辑 `.env`

```ini
SPRING_ARGS=
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
LLM_API_KEY=sk-你的key
LLM_MODEL=qwen3.7-plus
```

然后 `./deploy.sh start-real`（只重建后端容器，数据库不动）。想切回模拟模型用 `./deploy.sh start-mock`。

> 直连 DeepSeek 官方时 `LLM_BASE_URL=https://api.deepseek.com`，并把 `.env` 里的 `extra-body` 相关配置按 DeepSeek 文档改成关闭思考模式的参数（详见项目根 README）。

## 五、常用命令

| 命令 | 作用 |
| --- | --- |
| `./deploy.sh up` | 构建并启动全部服务 |
| `./deploy.sh ps` | 查看四个容器状态与健康情况 |
| `./deploy.sh logs` | 实时跟踪全部日志（`Ctrl+C` 退出，不影响容器） |
| `docker logs -f report-demo-backend` | 只看后端日志 |
| `./deploy.sh restart` | 重启所有容器（数据保留） |
| `./deploy.sh update` | 改完代码后只重建前后端并更新，MySQL / Redis 不动 |
| `./deploy.sh rebuild` | 不用缓存强制全量重建 |
| `./deploy.sh down` | 停止并删除容器，**数据卷保留** |
| `./deploy.sh destroy` | 停止并删除容器 + 数据卷，**数据全部清空**（会要求输入 yes） |

## 六、数据持久化

| 数据 | 位置 | 是否随容器删除 |
| --- | --- | --- |
| MySQL（会话、消息、审计、规则） | Docker 卷 `report-demo_mysql-data` | 否，仅 `destroy` 会删 |
| Redis（预览快照、待确认清单） | Docker 卷 `report-demo_redis-data` | 否，仅 `destroy` 会删 |

注意：报表表与规则表在后端每次启动时**重建并写入示例数据**（`schema.sql` / `data.sql` 的行为），
而 `agent_conversation`、`agent_message`、`dispatch_audit` 用 `IF NOT EXISTS` 创建，历史记录跨重启保留。

备份 MySQL：

```bash
docker exec report-demo-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" report_demo' > backup-$(date +%F).sql
```

## 七、架构与端口

```
浏览器 ──:80──> [frontend / Nginx]
                    ├── /            静态文件（Vue 构建产物）
                    └── /api/*   ──> [backend:8080] ──┬──> [mysql:3306]
                                                      └──> [redis:6379]
```

- 只有 `frontend` 对外暴露端口，`backend` 仅在内网（`expose`），MySQL / Redis 完全不出网
- 容器间通过服务名 `mysql` / `redis` / `backend` 互访，走 Docker 内置 DNS
- Nginx 对 `/api` 关掉了 `proxy_buffering`，SSE 流式返回才能逐字推给浏览器

## 八、常见问题

**构建时卡在下载依赖**
国内服务器给 Docker 配镜像加速：`/etc/docker/daemon.json` 写入 `{"registry-mirrors":["https://docker.mirrors.ustc.edu.cn"]}` 后 `sudo systemctl restart docker`。

**后端一直 starting / unhealthy**
`docker logs report-demo-backend` 看报错。常见原因是 MySQL 还没初始化完 —— compose 里已配 `depends_on: service_healthy`，若仍失败可 `./deploy.sh restart` 一次。
内存不足导致后端被 kill 时，把 `.env` 的 `JAVA_OPTS` 改成 `-Xms128m -Xmx512m`。

**页面能打开，但接口报错 / 对话一直转圈**
确认浏览器用的是服务器 IP 而不是 `localhost`，并且安全组已放行端口。SSE 若被中间的 SLB / CDN 缓冲，需要在其上同样关闭响应缓冲。

**改了代码怎么更新**
传到服务器后执行 `./deploy.sh update`。

**端口冲突**
`.env` 里改 `HTTP_PORT` 即可，容器内部仍是 80。

**彻底重来**
`./deploy.sh destroy` 后 `./deploy.sh up`。
