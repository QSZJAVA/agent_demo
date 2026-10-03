# ============================================================
# 后端：Spring Boot 3.5 + JDK 17，多阶段构建
# 构建上下文是项目根目录（docker compose 里已指定 context: .）
# ============================================================

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom，把依赖层单独缓存，改代码时不用重新拉依赖
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline

# 拷源码打包（-DskipTests 避免镜像构建期去连本地 MySQL / Redis）
COPY backend/src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 时区跟随 Asia/Shanghai，日志和 NOW() 才对得上
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC -Dfile.encoding=UTF-8"

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行
RUN groupadd -r app && useradd -r -g app -d /app app

COPY --from=build /build/target/report-demo-2.0.0.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# 独立就绪检查验证 MySQL 与 Redis；依赖不可用返回 HTTP 503，不依赖演示身份。
HEALTHCHECK --interval=15s --timeout=15s --start-period=60s --retries=10 \
    CMD curl -fsS --connect-timeout 2 --max-time 12 http://127.0.0.1:8080/api/health/readiness >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar $SPRING_ARGS"]
