# spring-jvm-lab

A Spring Boot 3.5 / Java 25 app built to produce JVM behavior on purpose (allocation churn,
humongous objects, a controllable memory leak, multi-hop requests), surrounded by a
production-style observability stack: Prometheus, Grafana, Loki, Tempo, Alertmanager, JFR,
GC logs and k6.

```bash
cp .env.example .env
docker compose up -d --build
open http://localhost:3000     # Grafana → Dashboards → JVM Overview (admin / admin)
```

- **[GUIDE.md](GUIDE.md)**: the guidebook. How the JVM manages memory, how garbage collection
  works (G1 in depth, plus ZGC, Shenandoah, Parallel and Serial), how each part of the stack
  works, and hands-on labs.
- **[docker/README.md](docker/README.md)**: ports, commands and file locations.
