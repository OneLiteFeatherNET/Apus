# syntax=docker/dockerfile:1

########################################
# Stage 1: build the SPA
########################################
# Version pinned to ui/.nvmrc (24); keep the two in sync -- and in sync with the runtime
# stage's distroless nodejs24 below, so the build and the server run the same major.
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
# Distroless, not nginx and not a plain `node:` image: this container hands out ~2 MB of
# pre-built files and needs nothing else. The image therefore contains a Node runtime, the
# generated assets and ui/serve.mjs -- no shell, no package manager, no libssl/PCRE/zlib
# stack, and no node_modules, because serve.mjs has zero dependencies. Everything a scanner
# used to report against the nginx base is simply not installed here.
#
# `:nonroot` runs as uid 65532 and needs no writable path at all, which is what lets the Helm
# chart set readOnlyRootFilesystem: true (nginx could not -- it wrote its pid and cache).
#
# Keep the nodejs24 tag in step with the build stage and ui/.nvmrc.
FROM gcr.io/distroless/nodejs24-debian12:nonroot

WORKDIR /app
COPY ui/serve.mjs ./serve.mjs
# serve.mjs defaults its document root to ./public next to itself, so no argument is needed
# below.
COPY --from=build /src/.output/public ./public

# Redundant with the :nonroot tag, but the uid is a contract the chart's runAsUser repeats --
# spelling it out here means a base-image swap that changes it fails visibly.
USER 65532:65532

# V8 sizes its heap from the *host's* RAM, not from the cgroup limit, so on a big node it
# would happily grow toward a ~4 GB heap inside a 256Mi container and be OOMKilled instead of
# collecting. Capping old space keeps the footprint the same on every node. Measured serving
# this build at concurrency 100: ~50 MB idle, ~100 MB peak with this cap (~115 MB without),
# which is what the chart's ui.resources are set against -- move the two together.
ENV NODE_OPTIONS=--max-old-space-size=64

EXPOSE 8080

# The distroless entrypoint is already ["/nodejs/bin/node"], so CMD carries only the script.
CMD ["/app/serve.mjs"]
