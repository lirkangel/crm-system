# Stage 1: build the static page from the committed lockfile.
FROM node:22-alpine AS build
WORKDIR /app
RUN npm install -g pnpm@11.1.1
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# Stage 2: nginx serving dist/ and proxying ONLY the hotel public check-in API to the CRM server.
# nginx-unprivileged runs as the non-root `nginx` user and listens on 8080.
FROM nginxinc/nginx-unprivileged:1.27-alpine
# Required at start (the image's envsubst step fills the template): CRM_SERVER_URL, CRM_PUBLIC_PROXY_SECRET.
# REAL_IP_FROM is the CIDR of the Cloudflare Tunnel connector; only it may set CF-Connecting-IP.
ENV REAL_IP_FROM=10.0.0.0/8
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
