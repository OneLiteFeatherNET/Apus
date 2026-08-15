# syntax=docker/dockerfile:1
#
# The Apus tenant application (image apus/ui). See ui/README.md, "Serving the built SPA", for
# why this image looks the way it does, and Dockerfile.console for its sibling.
#
# Build context is the repository root, matching what release-please.yml passes.

########################################
# Stage 1: build the SPA
########################################
# Version pinned to ui/.nvmrc (24); keep both in sync with the runtime stage below.
FROM node:24-bookworm-slim AS build

RUN corepack enable

WORKDIR /src
# Manifests first, so a source-only change reuses the install layer. `--filter @apus/ui-app...`
# resolves the app *and its workspace dependencies*, which is how the two layers get installed
# without this file having to name them.
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
COPY ui/apps/app/package.json ./apps/app/
COPY ui/layers/core/package.json ./layers/core/
COPY ui/layers/design/package.json ./layers/design/
RUN pnpm install --frozen-lockfile --filter @apus/ui-app...

COPY ui/ ./
# Not `nuxt generate`: that emits static files and no server for the CMD below to start.
RUN pnpm --filter @apus/ui-app build

########################################
# Stage 2: serve it
########################################
FROM gcr.io/distroless/nodejs24-debian12:nonroot

WORKDIR /app
COPY --from=build /src/apps/app/.output ./.output

# Already the default for the :nonroot tag; repeated because the chart's runAsUser must match.
USER 65532:65532

# Nitro would otherwise listen on 3000 and bind [::]; the chart says 8080 on an IPv4 cluster.
ENV PORT=8080
ENV HOST=0.0.0.0
# Without a cap V8 sizes its heap from the host's RAM, not the cgroup limit. Kept in step with
# ui.resources in the apus-platform chart.
ENV NODE_OPTIONS=--max-old-space-size=64

EXPOSE 8080

# The distroless entrypoint is already ["/nodejs/bin/node"].
CMD ["/app/.output/server/index.mjs"]
