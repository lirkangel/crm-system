# Stage 1: build the static page from the committed lockfile.
FROM node:22-alpine AS build
WORKDIR /app
RUN npm install -g pnpm@11.1.1
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
# Only what the build reads, so edits to nginx/ never bust the pnpm build cache.
COPY index.html vite.config.ts eslint.config.js tsconfig.json tsconfig.app.json tsconfig.node.json ./
COPY src ./src
RUN pnpm build

# Stage 2: nginx serving dist/ and proxying ONLY the hotel and dental public APIs (one per host) to the CRM server.
# nginx-unprivileged runs as the non-root `nginx` user and listens on 8080.
FROM nginxinc/nginx-unprivileged:1.27-alpine
# Required at start (checked by 05-check-env.sh, then the image's envsubst step fills the template):
#   CRM_SERVER_URL          scheme://host:port of the CRM server: NO path, NO trailing slash
#   CRM_PUBLIC_PROXY_SECRET [A-Za-z0-9_-]{32,}
# REAL_IP_FROM is the CIDR of the Cloudflare Tunnel connector; only it may set CF-Connecting-IP.
# The default trusts NO proxy: set it explicitly in the deployment.
# HOTEL_HOST / DENTAL_HOST are the two public host names (DNS names, different from each other); any other Host gets 404.
ENV REAL_IP_FROM=127.0.0.1/32
ENV HOTEL_HOST=checkin.mathisterben.org
ENV DENTAL_HOST=nhakhoa.mathisterben.org
COPY nginx/05-check-env.sh /docker-entrypoint.d/05-check-env.sh
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
# Shared parts: rendered by the same envsubst step into /etc/nginx/conf.d/snippets/ (not auto-included by nginx).
COPY nginx/site-common.conf.template /etc/nginx/templates/snippets/site-common.conf.template
COPY nginx/proxy-common.conf.template /etc/nginx/templates/snippets/proxy-common.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
