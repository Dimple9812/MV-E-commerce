# syntax=docker/dockerfile:1.7
# inventory-service image. Build from the repository root: docker build -f services/inventory-service/Dockerfile .
ARG SERVICE=inventory-service

FROM maven:3.9.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml ./
COPY libs ./libs
COPY services ./services
# The Maven cache mount keeps dependency downloads between builds without baking them into a layer.
# sharing=locked: Compose builds all services in parallel; without the lock eleven Maven processes would write
# the same cache at once (corrupting it) and hit Maven Central in parallel (HTTP 429). With it, the first build
# downloads the dependencies and the others reuse them. Retries cover transient 429/503 responses.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -pl services/${SERVICE} -am -DskipTests \
        -Daether.connector.http.retryHandler.count=10 \
        -Daether.connector.http.retryHandler.interval=3000 \
        -Daether.connector.http.retryHandler.serviceUnavailable=429,503 \
        package \
 && java -Djarmode=tools -jar services/${SERVICE}/target/${SERVICE}.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine AS runtime
ARG SERVICE
RUN addgroup -S app && adduser -S -G app -H app
WORKDIR /app
# Dependencies change least often, application classes most often: order the layers accordingly.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
EXPOSE 8084
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8084/actuator/health/readiness >/dev/null || exit 1
ENV SPRING_MAIN_BANNER_MODE=off \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
# Spring Boot handles SIGTERM with graceful shutdown (server.shutdown=graceful in marketflow-defaults.yml).
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
