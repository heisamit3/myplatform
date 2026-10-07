# syntax=docker/dockerfile:1
# web image: build the Vite app with Node, serve the static files with unprivileged nginx.
# Build from the repo root:  docker build -t myplatform/web:local web

# ---- build ----
FROM node:24.21.0-alpine3.24 AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
# The API base URL is compiled into the bundle. Empty = same origin: in Kubernetes, Traefik routes the
# API paths (/auth, /me, /orgs) to api-gateway and everything else to this image (ADR 0017).
ARG VITE_API_URL=""
ENV VITE_API_URL=${VITE_API_URL}
RUN npm run build

# ---- runtime ----
# Stable nginx line. The unprivileged variant listens on 8080 and runs as UID 101 (no root, no capabilities).
FROM nginxinc/nginx-unprivileged:1.30.5-alpine3.24
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
