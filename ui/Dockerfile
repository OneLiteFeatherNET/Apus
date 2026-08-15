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
# `nuxt build`, not `nuxt generate`: the nitro preset is node-server (see ui/nuxt.config.ts),
# so this writes both .output/server (the Nitro server) and .output/public (the client
# bundle). `generate` would emit static files and no server for the CMD below to start.
RUN pnpm build

########################################
# Stage 2: serve it
########################################
# Distroless, not nginx and not a plain `node:` image: the runtime needs a Node binary and
# .output, nothing else. No shell, no package manager, no OS-level TLS/PCRE/zlib stack -- the
# things a scanner used to report against the nginx base are simply not installed.
#
# .output/server does carry its own node_modules (Nitro externalises the Vue runtime and a
# handful of other packages), so unlike a dependency-free server this image *does* have an npm
# dependency tree for an SCA scanner to walk. That is the accepted cost of running Nitro's own
# server; those packages are the same ones the build already pins via pnpm-lock.yaml.
#
# `:nonroot` runs as uid 65532 and needs no writable path, which is what lets the Helm chart
# set readOnlyRootFilesystem: true (nginx could not -- it wrote its pid and cache).
#
# Keep the nodejs24 tag in step with the build stage and ui/.nvmrc.
FROM gcr.io/distroless/nodejs24-debian12:nonroot

WORKDIR /app
COPY --from=build /src/.output ./.output

# Redundant with the :nonroot tag, but the uid is a contract the chart's runAsUser repeats --
# spelling it out here means a base-image swap that changes it fails visibly.
USER 65532:65532

# Nitro defaults to port 3000 and binds [::]; the chart's containerPort and both probes say
# 8080, and the cluster this ships to is IPv4 (what nginx bound before). Note that PORT=0 does
# not mean "any free port" here -- Nitro reads it as unset and falls back to 3000.
ENV PORT=8080
ENV HOST=0.0.0.0

# V8 sizes its heap from the *host's* RAM, not the cgroup limit, so on a big node it would
# grow toward a ~4 GB heap inside a 256Mi container and be OOMKilled instead of collecting.
# Measured serving this build at concurrency 100: ~63 MB idle, ~115 MB peak with this cap
# (~153 MB without), which is what the chart's ui.resources are set against -- move the two
# together.
ENV NODE_OPTIONS=--max-old-space-size=64

EXPOSE 8080

# The distroless entrypoint is already ["/nodejs/bin/node"], so CMD carries only the script.
CMD ["/app/.output/server/index.mjs"]
