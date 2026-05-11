# VERA frontend — Vite build → nginx serve
FROM node:20-alpine AS build
WORKDIR /app
COPY client/package.json client/package-lock.json* ./
RUN npm ci --legacy-peer-deps
COPY client/ ./
# Build-time env: API/WS base URLs baked into the bundle
ARG VITE_API_BASE
ARG VITE_WS_BASE
ARG VITE_WAKE_PHRASES
ENV VITE_API_BASE=${VITE_API_BASE}
ENV VITE_WS_BASE=${VITE_WS_BASE}
ENV VITE_WAKE_PHRASES=${VITE_WAKE_PHRASES}
RUN npm run build

FROM nginx:1.27-alpine
RUN rm -rf /usr/share/nginx/html/* \
  && rm /etc/nginx/conf.d/default.conf
COPY client/nginx.conf /etc/nginx/conf.d/vera.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://localhost/ || exit 1
