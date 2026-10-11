# Kubernetes deployment

The workloads are packaged as a Helm chart in [`../helm/marketflow`](../helm/marketflow). This folder holds
the cluster-level pieces the chart deliberately does not own: the namespace and the secrets.

PostgreSQL, Kafka and Redis are **external dependencies** of the chart. In a cloud deployment use managed
services (for example RDS/Cloud SQL, MSK/Confluent Cloud, ElastiCache/Memorystore); in a dev cluster install
them with their upstream charts. Point the chart at them with `global.postgresHost`,
`global.kafkaBootstrapServers` and `global.redisHost`.

```bash
kubectl apply -f infrastructure/kubernetes/namespace.yaml
NS=marketflow ./infrastructure/kubernetes/create-secrets.sh
helm upgrade --install mf infrastructure/helm/marketflow -n marketflow \
  -f infrastructure/helm/marketflow/values-prod.yaml \
  --set image.registry=ghcr.io/<owner> --set image.tag=<git-sha> \
  --set ingress.host=shop.example.com
```

`values-dev.yaml` targets a local cluster (kind/k3d/minikube) with locally loaded images:

```bash
docker compose build                              # builds marketflow-<service> images
for s in gateway-service identity-service vendor-service catalog-service inventory-service order-service \
         payment-service notification-service review-service analytics-service frontend; do
  docker tag marketflow-$s marketflow/$s:dev && kind load docker-image marketflow/$s:dev
done
helm upgrade --install mf infrastructure/helm/marketflow -n marketflow -f infrastructure/helm/marketflow/values-dev.yaml
```

What the chart contains, per Java service: Deployment (startup/liveness/readiness probes on the actuator
health groups, resource requests and limits, non-root read-only container, graceful shutdown), Service,
HorizontalPodAutoscaler (CPU), PodDisruptionBudget, optional ServiceMonitor; plus the frontend, an Ingress
(`/api` to the gateway, `/` to the SPA), a NetworkPolicy, a ConfigMap of shared non-secret settings and the
catalog image PVC.

**Status:** the chart has been rendered for all three value sets and the output validated against the
Kubernetes 1.30 schemas with kubeconform. It has not been installed on a running cluster.
