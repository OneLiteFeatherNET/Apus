# Changelog

## [0.5.0](https://github.com/OneLiteFeatherNET/Apus/compare/apus-v0.4.1...apus-v0.5.0) (2026-08-15)


### Features

* **ui:** serve the dashboard with Nitro on distroless and make it configurable at runtime ([#47](https://github.com/OneLiteFeatherNET/Apus/issues/47)) ([962377a](https://github.com/OneLiteFeatherNET/Apus/commit/962377a0996c3f57ed09be5ed74d0e4010d3b1f2))

## [0.4.1](https://github.com/OneLiteFeatherNET/Apus/compare/apus-v0.4.0...apus-v0.4.1) (2026-08-15)


### Bug Fixes

* **observability:** load the operator and ingest logback configuration again ([#48](https://github.com/OneLiteFeatherNET/Apus/issues/48)) ([44f50ba](https://github.com/OneLiteFeatherNET/Apus/commit/44f50ba9b6b255f0001bcc48a9c9915400b0f7f1))

## [0.4.0](https://github.com/OneLiteFeatherNET/Apus/compare/apus-v0.3.1...apus-v0.4.0) (2026-08-15)


### Features

* **observability:** OpenTelemetry tracing and SLF4J logging ([#45](https://github.com/OneLiteFeatherNET/Apus/issues/45)) ([92c7f22](https://github.com/OneLiteFeatherNET/Apus/commit/92c7f22851ccfaa66e99a18abd6ff9e6ceeab40f))

## [0.3.1](https://github.com/OneLiteFeatherNET/Apus/compare/apus-v0.3.0...apus-v0.3.1) (2026-08-15)


### Bug Fixes

* **operator:** keep the process alive after the operator starts ([#43](https://github.com/OneLiteFeatherNET/Apus/issues/43)) ([718ae54](https://github.com/OneLiteFeatherNET/Apus/commit/718ae54c752c6e95a20089db32b18b4d7cee0eea))

## [0.3.0](https://github.com/OneLiteFeatherNET/Apus/compare/apus-v0.2.1...apus-v0.3.0) (2026-08-15)


### Features

* Helm charts for the operator and the platform ([#37](https://github.com/OneLiteFeatherNET/Apus/issues/37)) ([a03a524](https://github.com/OneLiteFeatherNET/Apus/commit/a03a524db16c72b29961af6428caed8a829afaaf))

## [0.2.1](https://github.com/OneLiteFeatherNET/Apus/compare/apus-v0.2.0...apus-v0.2.1) (2026-08-13)


### Bug Fixes

* **ci:** read the root release outputs without a path prefix ([#35](https://github.com/OneLiteFeatherNET/Apus/issues/35)) ([c0bd759](https://github.com/OneLiteFeatherNET/Apus/commit/c0bd759d62f407c1e6ab43de17ca7c73c972f2ff))

## [0.2.0](https://github.com/OneLiteFeatherNET/Apus/compare/apus-v0.1.0...apus-v0.2.0) (2026-08-13)


### Features

* package the API as a container image ([8c4c703](https://github.com/OneLiteFeatherNET/Apus/commit/8c4c7033d24734d0a9e77a625693aefddee355a5))
* package the dashboard as a static nginx container image ([d560ee5](https://github.com/OneLiteFeatherNET/Apus/commit/d560ee59da6e381cae653a3aa9da8aed68385efa))
* package the operator as a container image ([8f0b92e](https://github.com/OneLiteFeatherNET/Apus/commit/8f0b92e4a5336d0ff9133b9acc0e1f9e8a750cb7))
* Phase 7 — CI, release automation and container images ([12c0d81](https://github.com/OneLiteFeatherNET/Apus/commit/12c0d818789de7b3270d9ad24d280d321c5a1c09))
* publish telemetry-addon and paper-worldpush to the OneLiteFeather Maven repository ([8ef7d8f](https://github.com/OneLiteFeatherNET/Apus/commit/8ef7d8f85e86d76c66e9409ef5d4b3a03d29bdf6))


### Bug Fixes

* **release-please:** resolve extra-files per package and pin the full bootstrap sha ([0614747](https://github.com/OneLiteFeatherNET/Apus/commit/0614747e0e7b77292733d08c4574a486fdb2f159))
* **runner:** fetch the S3 storage addon in the image build ([e455c8d](https://github.com/OneLiteFeatherNET/Apus/commit/e455c8d6e0818ef18b3af2bd97a8408639b8618a))
