# syntax=docker/dockerfile:1
# notification-service image: compile TypeScript, run on Node as a non-root user.
# Build from the repo root:  docker build -t myplatform/notification-service:local services/notification-service
#
# All stages use the same base image: @confluentinc/kafka-javascript ships a native binary per libc
# (musl here), so the node_modules copied into the runtime must have been installed on Alpine too.
ARG NODE_IMAGE=node:24.21.0-alpine3.24

# ---- deps: production dependencies only (downloads the librdkafka prebuilt binary) ----
FROM ${NODE_IMAGE} AS deps
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --omit=dev --no-audit --no-fund

# ---- build: all dependencies, compile to dist/ ----
FROM ${NODE_IMAGE} AS build
WORKDIR /src
COPY package.json package-lock.json ./
# Install scripts aren't needed to compile (types only), so skip the native download here.
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts --no-audit --no-fund
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
# Tests run in CI and locally with Testcontainers; the image build only compiles.
RUN npm run build

# ---- runtime ----
FROM ${NODE_IMAGE}
# Fixed numeric UID/GID (same as the Java images): Kubernetes runAsNonRoot can only verify a numeric user.
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
ENV NODE_ENV=production
# Plain log lines: no ANSI color codes in `docker logs` or a log collector (Nest honors NO_COLOR).
ENV NO_COLOR=1
# Dependencies change less often than code, so they come first and stay cached.
COPY --from=deps /src/node_modules ./node_modules
COPY package.json ./
COPY --from=build /src/dist ./dist
USER 10001:10001
# V8 heap cap well below the 192M container limit: librdkafka's native buffers and threads live outside the heap.
ENV NODE_OPTIONS="--max-old-space-size=96"
EXPOSE 8082
# Node directly (not npm start): it receives SIGTERM itself and runs the graceful shutdown.
CMD ["node", "--enable-source-maps", "dist/main.js"]
