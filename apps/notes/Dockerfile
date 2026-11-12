# syntax=docker/dockerfile:1
# Static site: built on the CI/host architecture, served by arm64 nginx (no emulation).
# SITE_DOMAIN, SITE_TIMEZONE: from clusters/<cluster>/site-settings.yaml (CI build arguments).
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
ARG SITE_DOMAIN SITE_TIMEZONE
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# types + content schema, unit tests, build, then fail on any broken internal link/image
RUN npm run check && npm test && npm run build && npm run check:links

FROM nginxinc/nginx-unprivileged:1.27-alpine
ARG SITE_DOMAIN
ENV SITE_DOMAIN=${SITE_DOMAIN}
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
