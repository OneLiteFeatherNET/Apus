# Apus

Apus rendert Minecraft-Welten mit [BlueMap](https://bluemap.bluecolored.de/) auf Kubernetes
und hostet die Ergebnisse. Welt-Daten kommen aus mehreren, sehr unterschiedlichen Quellen;
ein ETL-Layer normalisiert sie, ein Operator führt Render- und Hosting-Jobs aus, eine
Oberfläche zeigt Fortschritt und erlaubt Bedienung ohne YAML.

Das vollständige Design steht in
[`docs/superpowers/specs/2026-08-08-apus-design.md`](docs/superpowers/specs/2026-08-08-apus-design.md).

## Module

| Modul | Zweck | Auslieferung |
| --- | --- | --- |
| `telemetry-addon` | BlueMap-Addon, exponiert Render-Fortschritt als JSON und Prometheus-Metriken | Maven |
| `ingest` | ETL: Connectoren (s3, pterodactyl, push, upload), Layout-Erkennung, Bundle-Writer | Container-Image |
| `runner` | BlueMap-CLI plus beide Addons, rendert eine Welt aus S3 nach S3 | Container-Image |
| `hosting` | Langlebiger BlueMap-Webserver, liest gerenderte Karten aus S3 | Container-Image |
| `operator` | Kubernetes-Operator, sechs CRDs, erzeugt Jobs/Deployments/Ingresses/Buckets | Container-Image |
| `api` | Micronaut-REST/SSE über den Custom Resources, Durchsetzungspunkt für Auth | Container-Image |
| `ui` | Nuxt-4-Dashboard für Mandanten und Plattform-Betreiber | Container-Image |
| `paper-worldpush` | Paper-Plugin, schiebt Welten vom laufenden Server nach Apus | Maven |

## Bauen

Voraussetzungen: JDK 25, Docker (für Integrationstests), pnpm (für `ui`).

    ./gradlew build          # alle Java-Module, ohne Integrationstests
    ./gradlew integrationTest # braucht Docker
    ./gradlew :operator:generateCrds  # erzeugt die sechs CRD-YAMLs nach operator/build/crds

    cd ui && pnpm install && pnpm test && pnpm lint

## Entwicklung

Der Kern des Systems ist das **World Bundle** — eine unveränderliche, normalisierte
Momentaufnahme einer Welt in S3. Links davon (Ingest) weiß niemand etwas von BlueMap,
rechts davon (Render, Hosting) niemand etwas von Pterodactyl oder ZIP-Uploads. Wer eine
neue Welt-Quelle anbindet, implementiert nur `WorldSourceConnector` in `ingest`.

Commits folgen [Conventional Commits](https://www.conventionalcommits.org/) — Release
Please leitet daraus Version und Changelog ab.

## Lizenz

AGPL-3.0, siehe [LICENSE](LICENSE).
