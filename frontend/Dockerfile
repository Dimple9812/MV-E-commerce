# syntax=docker/dockerfile:1.7
# Builds the SPA and serves it with unprivileged nginx (port 8080, non-root), which also reverse-proxies /api to the gateway so the browser sees a
# single origin (the HttpOnly refresh cookie is SameSite=Strict and scoped to /api/v1/auth).
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# GATEWAY_UPSTREAM is substituted into the template by the nginx image's entrypoint at start-up.
ENV GATEWAY_UPSTREAM=http://gateway-service:8080 \
    NGINX_ENVSUBST_FILTER=GATEWAY_UPSTREAM
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
