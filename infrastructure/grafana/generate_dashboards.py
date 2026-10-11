#!/usr/bin/env python3
"""Generates the provisioned Grafana dashboards from the metric names the services actually export.

Run after changing a panel:  python3 infrastructure/grafana/generate_dashboards.py
"""
import json
import pathlib

OUT = pathlib.Path(__file__).parent / "dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}
APP = '{application=~"$application"}'


# Failure counters are created on first use, so ungrouped ones get "or vector(0)" to show 0 instead of "No data".
def panel(pid, title, exprs, x, y, w=12, h=8, kind="timeseries", unit=None, legend=None, desc=None):
    targets = [{"refId": chr(65 + i), "expr": e, "legendFormat": (legend[i] if legend else "__auto"), "datasource": DS}
               for i, e in enumerate(exprs)]
    p = {"id": pid, "type": kind, "title": title, "datasource": DS, "targets": targets,
         "gridPos": {"x": x, "y": y, "w": w, "h": h},
         "fieldConfig": {"defaults": {"unit": unit or "short"}, "overrides": []},
         "options": {"legend": {"displayMode": "list", "placement": "bottom"}}}
    if desc:
        p["description"] = desc
    if kind == "stat":
        p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "area"}
    return p


def dashboard(uid, title, panels, app_var=True):
    templating = {"list": []}
    if app_var:
        templating["list"].append({
            "name": "application", "label": "Service", "type": "query", "datasource": DS,
            "query": {"query": "label_values(up, application)", "refId": "A"},
            "definition": "label_values(up, application)", "includeAll": True, "multi": True,
            "current": {"text": "All", "value": "$__all"}, "refresh": 2})
    return {"uid": uid, "title": title, "tags": ["marketflow"], "timezone": "browser", "schemaVersion": 39,
            "refresh": "30s", "time": {"from": "now-1h", "to": "now"}, "templating": templating,
            "panels": panels, "editable": True}


health = dashboard("mf-service-health", "MarketFlow / Service health", [
    panel(1, "Targets up", ['sum(up{job=~"marketflow-.*"})'], 0, 0, 6, 4, "stat"),
    panel(2, "Requests per second", [f'sum by (application) (rate(http_server_requests_seconds_count{APP}[5m]))'], 0, 4, unit="reqps"),
    panel(3, "5xx error ratio", [f'sum by (application) (rate(http_server_requests_seconds_count{{application=~"$application",status=~"5.."}}[5m])) / sum by (application) (rate(http_server_requests_seconds_count{APP}[5m]))'], 12, 4, unit="percentunit"),
    panel(4, "p95 latency", [f'histogram_quantile(0.95, sum by (application, le) (rate(http_server_requests_seconds_bucket{APP}[5m])))'], 0, 12, unit="s"),
    panel(5, "p99 latency", [f'histogram_quantile(0.99, sum by (application, le) (rate(http_server_requests_seconds_bucket{APP}[5m])))'], 12, 12, unit="s"),
    panel(6, "JVM heap used", [f'sum by (application) (jvm_memory_used_bytes{{application=~"$application",area="heap"}})'], 0, 20, unit="bytes"),
    panel(7, "DB connections in use", [f'sum by (application) (hikaricp_connections_active{APP})'], 12, 20),
    panel(8, "Gateway circuit breakers not closed", ['sum by (name, state) (resilience4j_circuitbreaker_state{application="gateway-service",state=~"open|half_open|forced_open"}) > 0'], 0, 28, 24, 6,
          desc="A non-empty series means the gateway is short-circuiting calls to that service."),
])

checkout = dashboard("mf-checkout", "MarketFlow / Checkout saga", [
    panel(1, "Checkouts started (1h)", ['sum(increase(checkout_requests_total{outcome="created"}[1h]))'], 0, 0, 6, 4, "stat"),
    panel(2, "Orders confirmed (1h)", ['sum(increase(checkout_completed_total{outcome="confirmed"}[1h]))'], 6, 0, 6, 4, "stat"),
    panel(3, "Idempotent replays (1h)", ['sum(increase(checkout_requests_total{outcome="replayed"}[1h]))'], 12, 0, 6, 4, "stat",
          desc="Retried checkout requests answered from the stored result instead of creating a second order."),
    panel(4, "Sagas awaiting reconciliation", ['max(saga_instances{state="RECONCILIATION_REQUIRED"})'], 18, 0, 6, 4, "stat"),
    panel(5, "Saga duration p95 by outcome", ['histogram_quantile(0.95, sum by (outcome, le) (rate(saga_duration_seconds_bucket[15m])))', 'sum by (outcome) (rate(saga_duration_seconds_sum[15m])) / sum by (outcome) (rate(saga_duration_seconds_count[15m]))'], 0, 4, unit="s",
          legend=["p95 {{outcome}}", "mean {{outcome}}"]),
    panel(6, "Sagas by state", ['sum by (state) (saga_instances)'], 12, 4, legend=["{{state}}"]),
    panel(7, "Compensations by reason", ['sum by (reason) (increase(saga_compensations_total[15m]))'], 0, 12, legend=["{{reason}}"]),
    panel(8, "Step failures and timeouts", ['sum by (step) (increase(saga_failures_total[15m]))', 'sum(increase(saga_timeouts_total[15m])) or vector(0)'], 12, 12, legend=["failure {{step}}", "timeouts"]),
    panel(9, "Inventory reservations", ['sum by (outcome) (rate(inventory_reservations_total[5m]))'], 0, 20, legend=["{{outcome}}"], unit="ops"),
    panel(10, "Payment outcomes", ['sum by (operation, result) (rate(payment_outcomes_total[5m]))'], 12, 20, legend=["{{operation}} {{result}}"], unit="ops"),
    panel(11, "Payment provider latency p95", ['histogram_quantile(0.95, sum by (operation, le) (rate(payment_provider_calls_seconds_bucket[5m])))'], 0, 28, unit="s", legend=["{{operation}}"]),
    panel(12, "Authorisation success ratio", ['sum(rate(payment_outcomes_total{operation="authorize",result="authorized"}[15m])) / sum(rate(payment_outcomes_total{operation="authorize"}[15m]))'], 12, 28, unit="percentunit"),
], app_var=False)

kafka = dashboard("mf-kafka", "MarketFlow / Kafka and outbox", [
    panel(1, "Max consumer lag", [f'max by (application, client_id) (kafka_consumer_fetch_manager_records_lag_max{APP})'], 0, 0, legend=["{{application}} {{client_id}}"]),
    panel(2, "Listener throughput", [f'sum by (application, messaging_kafka_consumer_group) (rate(spring_kafka_listener_seconds_count{APP}[5m]))'], 12, 0, unit="ops", legend=["{{messaging_kafka_consumer_group}}"]),
    panel(3, "Listener errors", [f'sum by (messaging_kafka_consumer_group, error) (rate(spring_kafka_listener_seconds_count{{application=~"$application",error!="none"}}[5m]))'], 0, 8, unit="ops"),
    panel(4, "Dead-lettered messages (1h)", [f'sum by (application) (increase(kafka_dlt_published_total{APP}[1h]))', 'sum(increase(notifications_dlt_total[1h])) or vector(0)'], 12, 8, legend=["{{application}}", "notification DLT"]),
    panel(5, "Outbox pending", [f'max by (application) (outbox_pending{APP})'], 0, 16),
    panel(6, "Outbox parked (FAILED)", [f'max by (application) (outbox_failed{APP})'], 12, 16,
          desc="Events that exhausted publish retries. Non-zero needs an operator; see docs/runbooks/outbox-failed.md."),
    panel(7, "Outbox publish rate", [f'sum by (application) (rate(outbox_published_total{APP}[5m]))', f'sum by (application) (rate(outbox_publish_failures_total{APP}[5m]))'], 0, 24, 24, unit="ops",
          legend=["published {{application}}", "failed {{application}}"]),
])

business = dashboard("mf-business", "MarketFlow / Business operations", [
    panel(1, "Logins", ['sum by (outcome) (increase(identity_logins_total[1h]))'], 0, 0, 8, 6, legend=["{{outcome}}"]),
    panel(2, "Registrations (1h)", ['sum(increase(identity_registrations_total[1h]))'], 8, 0, 8, 6, "stat"),
    panel(3, "Refunds", ['sum by (result) (increase(payment_refunds_total[1h]))'], 16, 0, 8, 6),
    panel(4, "Notifications delivered by template", ['sum by (template) (increase(notifications_delivered_total[1h]))'], 0, 6, legend=["{{template}}"]),
    panel(5, "Notification retries and failures", ['sum(increase(notifications_retries_total[1h])) or vector(0)', 'sum(increase(notifications_failed_total[1h])) or vector(0)'], 12, 6, legend=["retries", "failed"]),
    panel(6, "Catalog cache hit ratio", ['sum(rate(catalog_cache_total{result="hit"}[5m])) / sum(rate(catalog_cache_total[5m]))'], 0, 14, unit="percentunit"),
    panel(7, "Webhooks received", ['sum by (result) (increase(payment_webhooks_total[1h]))'], 12, 14, legend=["{{result}}"]),
], app_var=False)

OUT.mkdir(exist_ok=True)
for d in (health, checkout, kafka, business):
    (OUT / f"{d['uid']}.json").write_text(json.dumps(d, indent=2) + "\n")
    print("wrote", d["uid"])
