# syntax=docker/dockerfile:1.7
# Shared build recipe for every Spring Boot service. Each service's own Dockerfile is a copy with SERVICE/PORT
# filled in, so `docker build -f services/<svc>/Dockerfile .` works from the repository root (the build needs
# the parent POM and the shared libs/ modules).
ARG SERVICE=order-service

FROM maven:3.9.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml ./
COPY libs ./libs
COPY services ./services
# The Maven cache mount keeps dependency downloads between builds without baking them into a layer.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/${SERVICE} -am -DskipTests package \
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
ENV SPRING_MAIN_BANNER_MODE=off \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
# Spring Boot handles SIGTERM with graceful shutdown (server.shutdown=graceful in marketflow-defaults.yml).
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
