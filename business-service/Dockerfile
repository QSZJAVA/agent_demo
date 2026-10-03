FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY backend/pom.xml ./backend/pom.xml
COPY business-service/pom.xml ./business-service/pom.xml
COPY backend/src ./backend/src
COPY business-service/src ./business-service/src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai SERVER_ADDRESS=0.0.0.0
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd -r app && useradd -r -g app -d /app app
COPY --from=build /build/business-service/target/business-service-2.0.0.jar /app/app.jar
USER app
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:8090/health >/dev/null || exit 1
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
