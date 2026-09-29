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
RUN groupadd -r app && useradd -r -g app -d /app app
COPY --from=build /build/business-service/target/business-service-2.0.0.jar /app/app.jar
USER app
EXPOSE 8090
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
