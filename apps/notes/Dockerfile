# syntax=docker/dockerfile:1
# Static site: built on the CI/host architecture, served by arm64 nginx (no emulation).
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# types + content schema, unit tests, build, then fail on any broken internal link/image
RUN npm run check && npm test && npm run build && npm run check:links

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
