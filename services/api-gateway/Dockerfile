# syntax=docker/dockerfile:1
# api-gateway image: build with Gradle, run on a small JRE as a non-root user.
# Build from the repo root:  docker build -t myplatform/api-gateway:local services/api-gateway

# ---- build: compile and package the Spring Boot jar ----
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /src
# Wrapper and build files first: this layer (and the Gradle download) is reused until they change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --version >/dev/null
COPY src ./src
# Tests run in CI and locally with Testcontainers; the image build only packages.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar \
    && cp build/libs/api-gateway-*.jar application.jar
# Split the jar into layers (dependencies change rarely, application code often).
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- runtime ----
FROM eclipse-temurin:21.0.12.1_1-jre-alpine
# Fixed numeric UID/GID: Kubernetes runAsNonRoot can only verify a numeric user.
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
# Least-changing layer first, so a code change only rebuilds the last, small layer.
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
USER 10001:10001
# Heap = 40% of the container limit (ADR 0005): metaspace, code cache and native memory need the rest.
# Exit on OOM so the orchestrator restarts us instead of running half-broken.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
