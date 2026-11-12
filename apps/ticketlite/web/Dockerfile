# syntax=docker/dockerfile:1
# Build static files on the CI/host architecture, serve them from an arm64 nginx (no emulation).
# SITE_TIMEZONE: from clusters/<cluster>/site-settings.yaml (CI build argument).
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
ARG SITE_TIMEZONE
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm test && npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
ENV API_UPSTREAM=http://api:8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
