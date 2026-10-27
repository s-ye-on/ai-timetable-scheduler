FROM node:22-alpine AS build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src/ src/
COPY frontend/public/ public/
RUN npm run build

FROM nginx:stable-alpine
COPY --from=build /workspace/frontend/dist/ /usr/share/nginx/html/
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/bootstrap.conf /etc/nginx/conf.d/default.conf
COPY deploy/nginx/routes.conf /etc/nginx/snippets/routes.conf
EXPOSE 80 443
