# Logging and tracing

Apus' three long-running JVM services — `operator`, `api` and `ingest` — log through SLF4J
and emit OpenTelemetry traces. This document is the contract they share, so that a log line
from one service looks like a log line from the others and a trace crosses service
boundaries without anyone wiring it up per module.

## The split: console for humans, OTLP for the pipeline

Every service writes a readable line to the console **and** ships the same event through
OTLP. Those are two different audiences and neither replaces the other:

- **The console** is what `kubectl logs` shows. It exists so that someone debugging a pod at
  02:00 sees something legible without a query language in between.
- **OTLP** is the export path. Log records carry the active trace and span id, so a log line
  and the span it happened in are the same click apart in Grafana.

Nothing scrapes stdout for ingestion. That is the deliberate difference from the pattern the
rest of this cluster uses (Alloy tailing pod logs into Loki): the console format is free to
stay human-readable because no parser depends on it.

## `logback.xml`

Each service ships this file at `src/main/resources/logback.xml`. It is identical across
services except for the logger name in the last block.

```xml
<configuration>
  <!-- Console: for a human reading `kubectl logs`. No JSON here on purpose -- nothing
       parses this stream, so it can stay legible. -->
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- OTLP: the export path. Captures MDC and the code attributes so a log record can be
       correlated with the span it occurred in. With no OTEL_EXPORTER_OTLP_ENDPOINT set,
       the SDK is a no-op and this appender costs nothing. -->
  <appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
    <captureExperimentalAttributes>false</captureExperimentalAttributes>
    <captureCodeAttributes>true</captureCodeAttributes>
    <captureMdcAttributes>*</captureMdcAttributes>
  </appender>

  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="OTEL"/>
  </root>

  <!-- Our own code at DEBUG is useful while troubleshooting and cheap; the Kubernetes
       client at DEBUG is a firehose. -->
  <logger name="net.onelitefeather.apus" level="INFO"/>
  <logger name="io.fabric8.kubernetes.client" level="WARN"/>
</configuration>
```

The appender needs the SDK handed to it once at startup, before the first log line:

```java
OpenTelemetryAppender.install(openTelemetry);
```

## Configuration is environment, never code

The SDK is built by `AutoConfiguredOpenTelemetrySdk`, which reads the standard `OTEL_*`
variables. Which collector receives the data — or whether one exists at all — is a
deployment decision.

| Variable | Effect |
| --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Where traces and logs go. **Unset means the SDK is a no-op**, which is why this is safe to ship enabled by default. |
| `OTEL_SERVICE_NAME` | Shown as the service in Grafana. Set per deployment (`apus-operator`, `apus-api`, `apus-ingest`). |
| `OTEL_TRACES_SAMPLER` | Defaults to `parentbased_always_on`. |
| `OTEL_LOGS_EXPORTER` | `otlp` to export log records; `none` disables that half. |

## What to put in a span

A span should answer "what was the system doing, and how long did it take" for a unit of
work someone would ask about. Concretely, per service:

- **`operator`** — one span per reconciliation, named `<Kind> reconcile`, with the resource
  name and namespace as attributes. Nested spans for the steps that can be slow or fail on
  their own: provisioning a namespace, waiting on a Rook bucket, creating a Job.
- **`ingest`** — one span per ingest run, with child spans for extract, transform and load,
  and a span per dimension being written. The connector type belongs on the run span.
- **`api`** — HTTP server spans come from Micronaut's instrumentation. Add spans only where
  the work is not already one request: the informer cache warm-up, an SSE stream's lifetime.

Do not create a span per method call. A trace with fifty spans that all took 0 ms hides the
one that took nine seconds.

## Attributes and secrets

Attribute keys follow OpenTelemetry semantic conventions where one exists (`k8s.namespace.name`,
`k8s.pod.name`), and `apus.*` where none does (`apus.tenant`, `apus.map`, `apus.render.phase`).

Credentials, tokens and S3 keys never appear in a span attribute, a log line or an
exception message — the same rule the design spec sets for CR status and events (§12).

## Log levels

- `debug` — detail for an active investigation; not on in production.
- `info` — a business-meaningful event: a render started, a bundle was written, a tenant was
  provisioned. One line per event, not per method call.
- `warn` — recoverable but unexpected: a retry, a fallback, a slow external call.
- `error` — something failed in a way the caller sees, or state is now inconsistent.

Every class that logs owns a `private static final Logger LOGGER =
LoggerFactory.getLogger(<ThisClass>.class);`. A class with no logger because nobody added
one is the reason incidents take longer than they should.

## Modules deliberately left out

- **`telemetry-addon`** runs inside BlueMap's own classloader. It ships no dependency that
  could collide with BlueMap's classpath — that is why its HTTP server is the JDK's own
  (see its `TelemetryServer` javadoc). Adding an SDK there would trade a real risk for a
  marginal gain.
- **`paper-worldpush`** runs inside a Paper server and logs through Bukkit's logger, which
  the server owns and operators already know where to find.
- **`runner`** and **`hosting`** are shell entrypoints around the BlueMap CLI. Their output
  is BlueMap's own, and the operator already turns a render's progress into CR status.
