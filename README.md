# Apus

Apus renders Minecraft worlds with [BlueMap](https://bluemap.bluecolored.de/) on Kubernetes
and hosts the results. World data comes from several, very different sources; an ETL layer
normalizes it, an operator runs render and hosting jobs, and a UI shows progress and allows
operation without YAML.

The full design is in
[`docs/superpowers/specs/2026-08-08-apus-design.md`](docs/superpowers/specs/2026-08-08-apus-design.md).

## Modules

| Module | Purpose | Delivery |
| --- | --- | --- |
| `telemetry-addon` | BlueMap addon, exposes render progress as JSON and Prometheus metrics | Maven |
| `ingest` | ETL: connectors (s3, pterodactyl, push, upload), layout detection, bundle writer | Container image |
| `runner` | BlueMap CLI plus both addons, renders a world from S3 to S3 | Container image |
| `hosting` | Long-lived BlueMap web server, reads rendered maps from S3 | Container image |
| `operator` | Kubernetes operator, six CRDs, creates Jobs/Deployments/Ingresses/Buckets | Container image |
| `api` | Micronaut REST/SSE over the custom resources, enforcement point for auth | Container image |
| `ui` | Nuxt 4 dashboard for tenants and platform operators | Container image |
| `paper-worldpush` | Paper plugin, pushes worlds from the running server to Apus | Maven |

## Building

Prerequisites: JDK 25, Docker (for integration tests), pnpm (for `ui`).

    ./gradlew build          # all Java modules, without integration tests
    ./gradlew integrationTest # needs Docker
    ./gradlew :operator:generateCrds  # generates the six CRD YAMLs into operator/build/crds

    cd ui && pnpm install && pnpm test && pnpm lint

## Development

The core of the system is the **World Bundle** — an immutable, normalized
snapshot of a world in S3. On its left (ingest), nobody knows anything about BlueMap;
on its right (render, hosting), nobody knows anything about Pterodactyl or ZIP uploads.
Anyone connecting a new world source only needs to implement `WorldSourceConnector` in `ingest`.

Commits follow [Conventional Commits](https://www.conventionalcommits.org/) — Release
Please derives the version and changelog from them.

## License

AGPL-3.0, see [LICENSE](LICENSE).
