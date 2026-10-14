# Spring JVM Lab — Full Observability & GC Tuning Environment

## Context

This repo is a personal study case for production-style JVM monitoring: GC tuning, metrics, logs, traces, profiling, load testing, and alerting, all driven from Docker Compose so it mirrors a real prod observability stack. Prometheus + Grafana are already wired up but `docker-compose.yml` is currently broken (dangling `-` list entries under `ports`/`volumes` on both services, invalid YAML). `docker/loki/` and `docker/tempo/` exist as empty placeholder directories. The app itself (`SpringJvmLabApplication`, `HelloController`, `MemoryController`) currently runs on the host and is scraped via `host.docker.internal:8080`.

Decisions already confirmed: the app will be **containerized** (Dockerfile + compose service, with resource limits) rather than run on the host, and this plan covers the **full roadmap** — metrics, logs, traces, GC tuning, JFR, load generation, and alerting — executed as ordered phases in one session.

Outcome: `docker compose up -d` brings up app + Prometheus + Grafana + Loki + Promtail + Tempo + Alertmanager, all wired together, with the app exposing tunable GC behavior, a slow-leak endpoint, a multi-span trace endpoint, and continuous JFR recording — plus a k6 script to drive load.

## Phase 0 — Fix & restructure the base

- Rewrite `docker-compose.yml` from scratch (current one has invalid dangling list items). Fix ports/volumes for `prometheus` and `grafana`, put all services on one implicit default network so they reach each other by service name.
- Add `.gitignore` (currently missing — `target/` and `.idea/` show as untracked clutter in `git status`): ignore `target/`, `docker/app/gc-logs/`, `docker/app/jfr/`, `.env`.
- Remove the stray `https:/repo.maven.apache.org/maven2` directory at repo root — leftover from a misconfigured Maven invocation, not referenced anywhere (confirm nothing points at it before deleting).

## Phase 1 — Containerize the app

- Add root `Dockerfile`: multi-stage build. Build stage `maven:3.9-eclipse-temurin-25` (no `mvnw` present, so use the Maven image) running `mvn package -DskipTests`; runtime stage `eclipse-temurin:25-jre` copying the built jar, `ENTRYPOINT` runs `java $JAVA_OPTS -jar app.jar` so GC/JFR flags are swappable per run.
- Add root `.dockerignore` (`target/`, `.git/`, `.idea/`, `docker/`).
- Add `app` service to `docker-compose.yml`: builds from root Dockerfile, port `8080:8080`, `mem_limit`/`cpus` set to simulate constrained prod resources (e.g. `512m` / `1.0`), `JAVA_OPTS` sourced from a `.env`-overridable variable with a sane default (G1GC, `-Xms256m -Xmx256m`, GC logging to `/gc-logs/gc.log`, continuous JFR recording to `/jfr/continuous.jfr`), volumes mounting `./docker/app/gc-logs` and `./docker/app/jfr` for host-side access to logs/recordings.
- Update `docker/prometheus/prometheus.yml` scrape target from `host.docker.internal:8080` to `app:8080` (now reachable by service name).

## Phase 2 — GC tuning lab

- Extend `src/main/java/com/ndys/jvmlab/controller/MemoryController.java` (or add a `LeakService` under `service/`, following the existing controller→service pattern used by `HelloController`/`MessageService`) with a toggleable slow leak: `POST /lab/leak/start`, `POST /lab/leak/stop`, `POST /lab/leak/clear`, `GET /lab/leak/status` — a background task retaining byte arrays in a list so old-gen grows steadily until stopped, distinct from the existing `/lab/allocate` (which is short-lived young-gen churn).
- Document GC algorithm swapping in `docker/README.md`: default is G1 via `JAVA_OPTS` in `.env`; override examples for `-XX:+UseZGC`, `-XX:+UseShenandoahGC`, `-XX:+UseParallelGC`, `-XX:+UseSerialGC` (all available in `eclipse-temurin`, no build-specific GC needed on JDK 25), applied via `.env` + `docker compose up -d --build app`.
- Add a custom Grafana dashboard `docker/grafana/dashboards/jvm-overview.json` (provisioned automatically via the existing `dashboard.yml` provider): heap used/committed/max, GC pause time and count by cause, thread count, class count, CPU usage — sourced from the existing `micrometer-registry-prometheus` metrics, no dashboard-import dependency on network access.

## Phase 3 — Logs → Loki

- Add `docker/loki/loki-config.yml` (single-binary mode, local filesystem storage) and `docker/promtail/promtail-config.yml` (Docker service discovery via `/var/run/docker.sock` + `/var/lib/docker/containers`, so it picks up the `app` container's stdout automatically).
- Add `loki` (port `3100`) and `promtail` (no host port needed) services to `docker-compose.yml`.
- Enable Spring Boot's built-in structured JSON logging (no extra dependency needed on Boot 3.5): set `logging.structured.format.console: logstash` in `application.yml`.
- Extend `docker/grafana/provisioning/datasources/datasource.yml` with a `Loki` datasource pointing at `http://loki:3100`.

## Phase 4 — Traces → Tempo

- Add `docker/tempo/tempo.yml` (local storage backend, OTLP receiver on 4317/4318).
- Add `tempo` service to `docker-compose.yml`: ports `3200` (query), `4317` (OTLP gRPC), `4318` (OTLP HTTP).
- Add `micrometer-tracing-bridge-otel` and `io.opentelemetry:opentelemetry-exporter-otlp` to `pom.xml`; configure `management.tracing.sampling.probability: 1.0` and `management.otlp.tracing.endpoint: http://tempo:4318/v1/traces` in `application.yml`. Spring Boot auto-instruments incoming web requests and outgoing `RestTemplate`/`WebClient` calls once these are present — no manual span code needed. This also auto-populates `trace_id`/`span_id` in the Phase 3 structured logs, linking logs and traces.
- Add a new endpoint (e.g. `ChainController#/lab/chain`) that calls `/hello` via an injected `RestTemplateBuilder`-built client with a small artificial delay, to produce a multi-span trace worth inspecting.
- Extend the Grafana `Tempo` datasource (same provisioning file) with trace-to-logs correlation pointing at the `Loki` datasource, and confirm the actual `trace_id`/`span_id` field names emitted by Boot's structured logging match the correlation config once logs are flowing (verify live rather than assume).

## Phase 5 — JFR (Java Flight Recorder)

- No new application code: continuous recording is already enabled via the `JAVA_OPTS` flags from Phase 1 (`-XX:StartFlightRecording=...,disk=true,dumponexit=true,filename=/jfr/continuous.jfr`), persisted to `./docker/app/jfr` on the host for offline analysis (e.g. JDK Mission Control).
- Document in `docker/README.md` how to trigger an on-demand dump without restarting: `docker exec <app-container> jcmd 1 JFR.dump filename=/jfr/manual-$(date +%s).jfr` and `jcmd 1 JFR.check` to list active recordings.

## Phase 6 — Load generation (k6)

- Add `docker/k6/scripts/load-test.js`: ramping-VU script hitting `/hello`, `/lab/allocate`, `/lab/leak/*`, and `/lab/chain` so metrics/logs/traces have continuous, realistic traffic instead of being static.
- Add `k6` service (`grafana/k6` image) to `docker-compose.yml` gated behind `profiles: ["load"]` so it doesn't run on a plain `docker compose up`; invoked explicitly via `docker compose --profile load run k6 run /scripts/load-test.js`.

## Phase 7 — Alerting

- Add `docker/prometheus/alerts.yml` with example rules: high heap usage (`jvm_memory_used_bytes / jvm_memory_max_bytes > 0.85`), sustained GC pause time (`rate(jvm_gc_pause_seconds_sum[5m])` over a threshold), app down (`up == 0`), elevated 5xx rate on `http_server_requests_seconds_count`. Reference it via `rule_files` in `docker/prometheus/prometheus.yml`.
- Add `docker/alertmanager/alertmanager.yml` (simple default route, no external notification integration — the point is to observe alerts firing in the UI) and an `alertmanager` service on port `9093`.

## Phase 8 — Docs

- Rewrite `docker/README.md`: drop the "coming soon" notes, add a full service/port table, `.env` usage for `JAVA_OPTS`, and a short "labs" walkthrough — how to trigger the leak, swap GC algorithms and compare via the JVM dashboard, view a trace in Tempo and jump to its logs in Loki, pull a JFR recording, run the k6 load test, and see an alert fire.

## Verification

- `docker compose config` to confirm the rewritten compose file parses.
- `docker compose up -d --build` and confirm all containers stay healthy (`docker compose ps`).
- `curl localhost:8080/hello`, `/lab/allocate`, `/lab/leak/start` → `/lab/leak/status` → `/lab/leak/stop`, `/lab/chain`.
- Prometheus UI (`:9090`) — target `app` is `UP`, alert rules loaded under `/alerts`.
- Grafana (`:3000`) — JVM dashboard renders live data; Loki datasource shows app logs; Tempo datasource shows the `/lab/chain` trace with a working logs link.
- `docker exec` into the app container, run `jcmd 1 JFR.check`, confirm the continuous recording is active; confirm `./docker/app/gc-logs/gc.log` is being written on the host.
- Swap `JAVA_OPTS` in `.env` to `-XX:+UseZGC`, rebuild `app`, confirm it still starts and the dashboard reflects the different GC.
- `docker compose --profile load run k6 run /scripts/load-test.js` and watch traffic show up across Grafana panels while it runs.
