# Docker stack

Operational cheat sheet. For explanations of how everything works, see [`../GUIDE.md`](../GUIDE.md).

## Services

| Service | Image | Host port(s) | Config |
|---|---|---|---|
| app | built from `../Dockerfile` | 8080 | `../.env` (`JAVA_OPTS`), `../src/main/resources/application.yml` |
| prometheus | `prom/prometheus` | 9090 | `prometheus/prometheus.yml`, `prometheus/alerts.yml` |
| alertmanager | `prom/alertmanager:v0.28.1` | 9093 | `alertmanager/alertmanager.yml` |
| grafana | `grafana/grafana` | 3000 (`admin`/`admin`) | `grafana/provisioning/`, `grafana/dashboards/` |
| loki | `grafana/loki:3.5.0` | 3100 | `loki/loki-config.yml` |
| alloy | `grafana/alloy:v1.9.1` | 12345 (pipeline UI) | `alloy/config.alloy` |
| tempo | `grafana/tempo:2.8.1` | 3200, 4317 (OTLP gRPC), 4318 (OTLP HTTP) | `tempo/tempo.yml` |
| k6 | `grafana/k6:1.0.0` | none (`load` profile only) | `k6/scripts/load-test.js` |

## Everyday commands

```bash
cp .env.example .env                                   # once
docker compose up -d --build                           # start / rebuild everything
docker compose up -d app                               # apply a changed .env (no rebuild needed)
docker compose up -d --build app                       # apply changed Java code
docker compose logs -f app                             # follow app logs
docker compose --profile load run --rm k6 run /scripts/load-test.js
curl -X POST localhost:9090/-/reload                   # reload Prometheus config / alert rules
docker compose down                                    # stop and remove everything
```

## Swap the garbage collector

Edit the `-XX:+UseG1GC` flag in `../.env`, then `docker compose up -d app`.
Options: `-XX:+UseZGC`, `-XX:+UseShenandoahGC`, `-XX:+UseParallelGC`, `-XX:+UseSerialGC`.

## JVM output on the host

| Path | Written by |
|---|---|
| `app/gc-logs/gc.log` | `-Xlog:gc*` (unified GC logging) |
| `app/jfr/continuous.jfr` | JFR, on clean JVM exit (`dumponexit=true`) |
| `app/jfr/*.jfr` | Manual dumps (below) |
| `app/dumps/java_pid1.hprof` | `-XX:+HeapDumpOnOutOfMemoryError` |

## JFR and jcmd

The runtime image has `jfr` but not `jcmd`, so use a throwaway JDK container that joins the
app's process namespace:

```bash
docker run --rm --pid=container:app eclipse-temurin:25-jdk jcmd 1 JFR.check
docker run --rm --pid=container:app eclipse-temurin:25-jdk jcmd 1 JFR.dump name=1 filename=/jfr/manual.jfr
docker exec app jfr summary /jfr/manual.jfr
docker run --rm --pid=container:app eclipse-temurin:25-jdk jcmd 1 Thread.print   # thread dump
docker run --rm --pid=container:app eclipse-temurin:25-jdk jcmd 1 GC.heap_info
```
