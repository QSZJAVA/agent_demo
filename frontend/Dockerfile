# ============================================================
# 前端：Vue 2.7 + Vite 构建，Nginx 托管静态文件并反代 /api
# 构建上下文是项目根目录（docker-compose.yml 里指定 context: .）
# ============================================================

# ---------- 阶段一：构建 ----------
FROM node:20-alpine AS build
WORKDIR /build

# 依赖层缓存
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# ---------- 阶段二：Nginx ----------
FROM nginx:1.27-alpine

# 删掉默认站点，换成我们自己的配置
RUN rm -f /etc/nginx/conf.d/default.conf

# 页面应用配置由一键脚本按服务器 IP 生成后挂载到容器里（见 docker-compose.yml）
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
