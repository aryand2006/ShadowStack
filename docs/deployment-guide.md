# ShadowStack Deployment Guide

## Prerequisites

| Tool | Minimum Version | Purpose |
|---|---|---|
| Java (JDK) | 21 | Build and run ShadowStack services |
| Maven | 3.9+ | Multi-module build |
| Node.js | 20+ | Web dashboard |
| Docker | 24+ | Container runtime |
| Docker Compose | 2.20+ | Local development stack |
| kubectl | 1.29+ | Kubernetes deployment |
| Helm | 3.14+ | Kubernetes package management (optional) |

---

## 1. Local Development with Docker Compose

### Quick Start

```bash
# Clone the repository
git clone https://github.com/shadowstack/shadowstack.git
cd shadowstack

# Run the setup script
./scripts/setup.sh

# Or manually start the full stack
docker compose up -d
```

### Docker Compose Stack

Create or verify `docker-compose.yml` in the project root:

```yaml
version: "3.9"

services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: shadowstack-db
    environment:
      POSTGRES_DB: shadowstack
      POSTGRES_USER: shadowstack
      POSTGRES_PASSWORD: shadowstack
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U shadowstack"]
      interval: 5s
      timeout: 5s
      retries: 5

  api:
    build:
      context: .
      dockerfile: apps/api/Dockerfile
    container_name: shadowstack-api
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: shadowstack
      DB_USERNAME: shadowstack
      DB_PASSWORD: shadowstack
      JWT_SECRET: "your-256-bit-secret-key-for-development-only-change-in-prod"
      CORS_ORIGINS: "http://localhost:3000"
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health/liveness"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  worker:
    build:
      context: .
      dockerfile: apps/worker/Dockerfile
    container_name: shadowstack-worker
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: shadowstack
      DB_USERNAME: shadowstack
      DB_PASSWORD: shadowstack

  web:
    build:
      context: apps/web
      dockerfile: Dockerfile
    container_name: shadowstack-web
    depends_on:
      - api
    environment:
      NEXT_PUBLIC_API_URL: "http://localhost:8080/api/v1"
    ports:
      - "3000:3000"

  prometheus:
    image: prom/prometheus:v2.51.0
    container_name: shadowstack-prometheus
    volumes:
      - ./infra/prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:10.4.0
    container_name: shadowstack-grafana
    depends_on:
      - prometheus
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: shadowstack
    ports:
      - "3001:3000"
    volumes:
      - grafana-data:/var/lib/grafana

volumes:
  pgdata:
  grafana-data:
```

### Service URLs

| Service | URL | Credentials |
|---|---|---|
| API | http://localhost:8080 | JWT token (see below) |
| Swagger UI | http://localhost:8080/swagger-ui.html | — |
| Web Dashboard | http://localhost:3000 | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3001 | admin / shadowstack |
| PostgreSQL | localhost:5432 | shadowstack / shadowstack |

### Verify the Stack

```bash
# Check API health
curl -s http://localhost:8080/actuator/health | jq .

# Obtain a JWT token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"shadowstack"}' | jq -r .token)

# List projects
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/projects | jq .
```

---

## 2. Environment Variables Reference

### API Server

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `shadowstack` | Database name |
| `DB_USERNAME` | `shadowstack` | Database username |
| `DB_PASSWORD` | `shadowstack` | Database password |
| `JWT_SECRET` | (dev placeholder) | **Must change in production.** Minimum 256-bit random key |
| `CORS_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed origins |
| `SECURITY_USER` | `admin` | Default admin username |
| `SECURITY_PASSWORD` | `shadowstack` | Default admin password |
| `SERVER_PORT` | `8080` | API server port |

### Application Configuration (`shadowstack.*`)

| Property | Default | Description |
|---|---|---|
| `shadowstack.risk.low-threshold` | `0.3` | Risk score below this = LOW tier |
| `shadowstack.risk.medium-threshold` | `0.6` | Risk score below this = MEDIUM tier |
| `shadowstack.risk.high-threshold` | `0.85` | Risk score below this = HIGH tier |
| `shadowstack.risk.auto-apply-enabled` | `false` | Enable auto-apply for low-risk patches |
| `shadowstack.risk.max-auto-apply-risk` | `0.2` | Maximum risk score for auto-apply |
| `shadowstack.retention.audit-log-days` | `365` | Audit log retention period |
| `shadowstack.retention.patch-history-days` | `180` | Patch history retention period |
| `shadowstack.retention.verification-evidence-days` | `90` | Verification evidence retention |
| `shadowstack.pipeline.max-concurrent-analyses` | `4` | Max concurrent analysis jobs |
| `shadowstack.pipeline.verification-timeout-seconds` | `300` | Verification pipeline timeout |
| `shadowstack.pipeline.default-language` | `java` | Default source language |
| `shadowstack.security.jwt-expiration-ms` | `86400000` | JWT token expiration (24h) |

### Worker

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `shadowstack` | Database name |
| `DB_USERNAME` | `shadowstack` | Database username |
| `DB_PASSWORD` | `shadowstack` | Database password |
| `WORKER_THREADS` | `4` | Number of worker threads |

### Web Dashboard

| Variable | Default | Description |
|---|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080/api/v1` | Backend API URL |

---

## 3. Database Setup

### PostgreSQL with pgvector

ShadowStack requires PostgreSQL 16+ with the [pgvector](https://github.com/pgvector/pgvector) extension for embedding similarity search.

```sql
-- Create database (if not using Docker)
CREATE DATABASE shadowstack;
CREATE USER shadowstack WITH PASSWORD 'shadowstack';
GRANT ALL PRIVILEGES ON DATABASE shadowstack TO shadowstack;

-- Enable pgvector extension
\c shadowstack
CREATE EXTENSION IF NOT EXISTS vector;
```

### Schema Migrations

ShadowStack uses Flyway for database migrations. Migrations run automatically on startup.

```bash
# Check migration status
curl -s http://localhost:8080/actuator/flyway | jq .

# Manually run migrations (if needed)
mvn flyway:migrate -pl apps/api \
  -Dflyway.url=jdbc:postgresql://localhost:5432/shadowstack \
  -Dflyway.user=shadowstack \
  -Dflyway.password=shadowstack
```

Migration files are located at `apps/api/src/main/resources/db/migration/`.

### Database Security

For production, configure the following:

```sql
-- Create a restricted application user
CREATE ROLE shadowstack_app WITH LOGIN PASSWORD 'strong-password';
GRANT CONNECT ON DATABASE shadowstack TO shadowstack_app;
GRANT USAGE ON SCHEMA public TO shadowstack_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO shadowstack_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO shadowstack_app;

-- Restrict audit log modifications
REVOKE DELETE, UPDATE ON audit_log FROM shadowstack_app;

-- Create a read-only reporting role
CREATE ROLE shadowstack_readonly WITH LOGIN PASSWORD 'readonly-password';
GRANT CONNECT ON DATABASE shadowstack TO shadowstack_readonly;
GRANT USAGE ON SCHEMA public TO shadowstack_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO shadowstack_readonly;
```

---

## 4. Kubernetes Deployment

### Namespace Setup

```bash
kubectl create namespace shadowstack
kubectl create namespace shadowstack-data
```

### Secrets

```bash
# Create database secret
kubectl create secret generic shadowstack-db \
  --namespace shadowstack \
  --from-literal=username=shadowstack \
  --from-literal=password='<strong-random-password>'

# Create JWT secret (minimum 256-bit random key)
JWT_KEY=$(openssl rand -base64 48)
kubectl create secret generic shadowstack-jwt \
  --namespace shadowstack \
  --from-literal=secret="$JWT_KEY"
```

### API Deployment

```yaml
# k8s/api-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: shadowstack-api
  namespace: shadowstack
  labels:
    app: shadowstack-api
spec:
  replicas: 2
  selector:
    matchLabels:
      app: shadowstack-api
  template:
    metadata:
      labels:
        app: shadowstack-api
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      serviceAccountName: shadowstack-api
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      containers:
        - name: api
          image: shadowstack/api:1.0.0
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: DB_HOST
              value: postgres.shadowstack-data.svc.cluster.local
            - name: DB_PORT
              value: "5432"
            - name: DB_NAME
              value: shadowstack
            - name: DB_USERNAME
              valueFrom:
                secretKeyRef:
                  name: shadowstack-db
                  key: username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: shadowstack-db
                  key: password
            - name: JWT_SECRET
              valueFrom:
                secretKeyRef:
                  name: shadowstack-jwt
                  key: secret
          resources:
            requests:
              cpu: 500m
              memory: 512Mi
            limits:
              cpu: 2000m
              memory: 2Gi
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 15
            periodSeconds: 5
          startupProbe:
            httpGet:
              path: /actuator/health
              port: http
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 12
```

### Horizontal Pod Autoscaler

```yaml
# k8s/api-hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: shadowstack-api
  namespace: shadowstack
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: shadowstack-api
  minReplicas: 2
  maxReplicas: 5
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

### Service and Ingress

```yaml
# k8s/api-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: shadowstack-api
  namespace: shadowstack
spec:
  selector:
    app: shadowstack-api
  ports:
    - port: 80
      targetPort: 8080
      protocol: TCP
  type: ClusterIP
---
# k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: shadowstack
  namespace: shadowstack
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/rate-limit: "100"
    nginx.ingress.kubernetes.io/rate-limit-window: "1m"
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - shadowstack.example.com
      secretName: shadowstack-tls
  rules:
    - host: shadowstack.example.com
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: shadowstack-api
                port:
                  number: 80
          - path: /
            pathType: Prefix
            backend:
              service:
                name: shadowstack-web
                port:
                  number: 80
```

### Network Policy

```yaml
# k8s/network-policy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: shadowstack-api-policy
  namespace: shadowstack
spec:
  podSelector:
    matchLabels:
      app: shadowstack-api
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: shadowstack
      ports:
        - port: 8080
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              name: shadowstack-data
      ports:
        - port: 5432
    - to:
        - namespaceSelector:
            matchLabels:
              name: kube-system
      ports:
        - port: 53
          protocol: UDP
```

### Deploy

```bash
# Apply all manifests
kubectl apply -f k8s/

# Verify deployment
kubectl get pods -n shadowstack
kubectl get svc -n shadowstack
kubectl get ingress -n shadowstack

# Check API health
kubectl port-forward svc/shadowstack-api 8080:80 -n shadowstack &
curl -s http://localhost:8080/actuator/health | jq .
```

---

## 5. Monitoring and Health Checks

### Actuator Endpoints

| Endpoint | Purpose | Auth Required |
|---|---|---|
| `/actuator/health` | Overall health | No |
| `/actuator/health/liveness` | Kubernetes liveness probe | No |
| `/actuator/health/readiness` | Kubernetes readiness probe | No |
| `/actuator/info` | Application info | No |
| `/actuator/metrics` | Micrometer metrics | Yes |
| `/actuator/prometheus` | Prometheus scrape target | Yes |

### Key Metrics

| Metric | Description |
|---|---|
| `shadowstack_analyses_total` | Total analyses run |
| `shadowstack_patches_generated_total` | Total patches generated |
| `shadowstack_patches_verified_total` | Total patches verified |
| `shadowstack_patches_accepted_total` | Total patches accepted |
| `shadowstack_verification_duration_seconds` | Verification pipeline duration histogram |
| `shadowstack_review_duration_seconds` | Time from patch creation to review |
| `shadowstack_corpus_entries_total` | Total corpus entries |
| `hikaricp_connections_active` | Active database connections |
| `jvm_memory_used_bytes` | JVM memory usage |

### Prometheus Configuration

```yaml
# infra/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'shadowstack-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['shadowstack-api:8080']
    basic_auth:
      username: admin
      password: shadowstack

  - job_name: 'shadowstack-worker'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['shadowstack-worker:8081']
```

### Grafana Alerting Rules

| Alert | Condition | Severity |
|---|---|---|
| APIHighErrorRate | 5xx rate > 5% for 5 min | Critical |
| VerificationTimeout | Verification duration > 300s | Warning |
| DBConnectionPoolExhausted | Active connections > 18 | Critical |
| HighMemoryUsage | JVM memory > 80% limit | Warning |
| ReviewQueueBacklog | Pending reviews > 100 | Warning |
| CorpusCalibrationDrift | ECE > 0.1 | Warning |

---

## 6. Production Checklist

- [ ] **JWT Secret**: Generate cryptographic random key (`openssl rand -base64 48`)
- [ ] **Database Password**: Use a strong, unique password via secret management
- [ ] **TLS**: Configure TLS 1.3 on Ingress with valid certificate
- [ ] **CORS Origins**: Restrict to production domain(s) only
- [ ] **Auto-Apply**: Confirm `auto-apply-enabled: false` unless explicitly desired
- [ ] **Resource Limits**: Set CPU/memory limits on all pods
- [ ] **Network Policies**: Restrict pod-to-pod and egress communication
- [ ] **Monitoring**: Prometheus scraping configured and Grafana dashboards imported
- [ ] **Alerting**: Alert rules configured for error rate, latency, and resource usage
- [ ] **Backup**: PostgreSQL backup schedule configured (pg_dump or WAL archiving)
- [ ] **Audit Log Export**: Configure export to immutable log aggregator
- [ ] **Container Images**: Scan for vulnerabilities, use distroless/slim base
- [ ] **Security Context**: Non-root, read-only filesystem, seccomp profiles
- [ ] **Flyway Baseline**: Run `flyway baseline` on first production deployment
