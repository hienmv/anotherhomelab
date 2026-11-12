# syntax=docker/dockerfile:1
# Landing page (apex of the site domain): built on the CI/host architecture, served by nginx.
# SITE_DOMAIN: from clusters/<cluster>/site-settings.yaml (CI passes it as a build argument).
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
ARG SITE_DOMAIN
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run check && npm test && npm run build && npm run check:links

FROM nginxinc/nginx-unprivileged:1.27-alpine
ENV NOTES_UPSTREAM=http://notes.apps.svc.cluster.local \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
