# Docker Infrastructure

This directory contains the local observability stack.

## Components

- Prometheus: Metrics collection
- Grafana: Metrics visualization
- Loki: Log aggregation (coming soon)
- Tempo: Distributed tracing (coming soon)

## Directory Structure

grafana/
├── dashboards/         # Dashboard JSON files
└── provisioning/       # Auto provisioning for Grafana

prometheus/
└── prometheus.yml      # Scrape configuration