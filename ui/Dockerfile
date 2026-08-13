# syntax=docker/dockerfile:1

########################################
# Stage 1: build the SPA
########################################
# Version pinned to ui/.nvmrc (24); keep the two in sync.
FROM node:24-bookworm-slim AS build

RUN corepack enable

WORKDIR /src
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY ui/ ./
RUN pnpm generate

########################################
# Stage 2: serve it
########################################
FROM nginxinc/nginx-unprivileged:1.29-alpine

# The unprivileged nginx image already runs as uid 101; it needs no writable root and
# listens on 8080 rather than 80, which is why it is used instead of the stock image.
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/.output/public /usr/share/nginx/html

EXPOSE 8080
