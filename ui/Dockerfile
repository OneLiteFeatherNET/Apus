# syntax=docker/dockerfile:1
#
# See ui/README.md, "Serving the built SPA", for why this image looks the way it does.

########################################
# Stage 1: build the SPA
########################################
# Version pinned to ui/.nvmrc (24); keep both in sync with the runtime stage below.
FROM node:24-bookworm-slim AS build

RUN corepack enable

WORKDIR /src
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY ui/ ./
# Not `pnpm generate`: that emits static files and no server for the CMD below to start.
RUN pnpm build

########################################
# Stage 2: serve it
########################################
FROM gcr.io/distroless/nodejs24-debian12:nonroot

WORKDIR /app
COPY --from=build /src/.output ./.output

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
