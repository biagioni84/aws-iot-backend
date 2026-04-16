# aws-iot-backend

Spring Boot 4 backend for a multi-tenant IIoT gateway platform. Manages gateway registration, SSH tunnel lifecycle, and real-time device communication over MQTT.

---

## Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 17 |
| Framework | Spring Boot 4.0.2 |
| Persistence | Spring Data JPA + Hibernate 7 + PostgreSQL 16 |
| Schema migrations | Flyway |
| Security | Spring Security + JWT (JJWT) |
| Messaging | AWS IoT Core MQTT5 (AWS IoT Device SDK) |
| Telemetry storage | AWS DynamoDB (async SDK v2) |
| Cold storage | AWS S3 (Parquet via Apache Parquet + Avro) |
| Analytics | AWS Athena (async SDK v2) |
| Connection pool | HikariCP |
| Build | Gradle |

---

## Architecture Overview

```
Frontend (React)
    │
    ▼ REST /api/v1/**
Spring Boot Backend
    ├── JwtAuthenticationFilter   — validates Bearer token
    ├── GatewayOwnershipFilter    — enforces per-request gateway ownership (DB lookup)
    ├── AdminController           — user summary, gateway list
    ├── TunnelController          — tunnel CRUD + start/stop
    ├── TelemetryController       — unified hot+cold telemetry query
    └── LoginController           — authentication
         │
         ├── GatewayService       — synchronous JPA service
         ├── MqttService          — MQTT5 pub/sub, request/response + telemetry ingest
         ├── TelemetryService     — DynamoDB hot storage (last 48 h)
         ├── ArchiveService       — daily Parquet archival to S3 (scheduled)
         ├── AthenaService        — cold query via Athena (>48 h)
         ├── PortPoolService      — SSH port assignment
         └── LightsailRemoteAccess — authorized_keys + Lightsail firewall management
              │
              ├── PostgreSQL (via Docker)
              ├── AWS IoT Core (MQTT)
              ├── AWS DynamoDB (iot-telemetry — hot, TTL 48 h)
              ├── AWS S3 (Parquet cold archive)
              └── AWS Athena (cold queries)
```

### MQTT Request/Response Pattern

Each command to a gateway is sent to `iot/v1/{gatewayId}/request/{correlationId}` and awaits a response on `iot/v1/{gatewayId}/response/{correlationId}`. Pending requests are tracked with `CompletableFuture` and timed out automatically.

### Telemetry Ingest Pipeline

```
Gateway → MQTT iot/v1/{gatewayId}/status → MqttService → TelemetryService → DynamoDB iot-telemetry
```

Each message on `iot/v1/{gatewayId}/status` (~3 KB JSON, ~1 msg/min/gateway) is persisted as a single DynamoDB item. The write is fire-and-forget — errors are logged but never propagate back to the MQTT thread.

### Telemetry Storage Tiers

```
DynamoDB iot-telemetry  ←  hot tier   (last 48 h, TTL auto-deletes older items)
         │
         │  ArchiveService  (daily cron, 2 AM UTC)
         ▼
S3 Parquet files         ←  cold tier  (partitioned by dt + gateway_id for Athena)
         │
         │  AthenaService
         ▼
Athena SQL queries       ←  unified    (TelemetryController routes transparently)
```

`GET /api/v1/{gwId}/telemetry?from=&to=` automatically routes to DynamoDB, Athena, or both based on whether the requested range falls within the 48 h hot window.

---

## Domain Model

```
User ──< Gateway ──< Tunnel
```

- **User** — platform user, owns one or more gateways
- **Gateway** — edge device identified by its MQTT client ID (string PK), status: ONLINE / OFFLINE / UNKNOWN
- **Tunnel** — SSH reverse tunnel configuration, state: STOPPED / ACTIVE / ERROR, with an optional `assignedPort`

---

## API Endpoints

All endpoints require `Authorization: Bearer <token>` except `/auth/login`.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/login` | Authenticate, returns JWT |
| GET | `/api/v1/summary` | User profile + gateway list with tunnel counts |
| GET | `/api/v1/{gwId}/tunnels` | List tunnels for a gateway |
| GET | `/api/v1/{gwId}/tunnels/{tunnelId}` | Tunnel detail |
| POST | `/api/v1/{gwId}/tunnels` | Create tunnel |
| PUT | `/api/v1/{gwId}/tunnels/{tunnelId}` | Update tunnel |
| DELETE | `/api/v1/{gwId}/tunnels/{tunnelId}` | Delete tunnel (sends stop to gateway first) |
| POST | `/api/v1/{gwId}/tunnels/{tunnelId}/start` | Start tunnel (MQTT + port assignment) |
| POST | `/api/v1/{gwId}/tunnels/{tunnelId}/stop` | Stop tunnel (MQTT + SSH kill + port release) |
| GET | `/api/v1/gateways` | Live gateway status via MQTT |
| GET | `/api/v1/{gwId}/proxy/{path}` | Proxy HTTP request through gateway |
| GET | `/api/v1/{gwId}/telemetry?from=&to=` | Query sensor telemetry — routes to DynamoDB, Athena, or both |

Full interactive docs available at `/swagger-ui.html` when running.

---

## Security

**JWT** — tokens are issued at login and contain the username and roles. Gateway ownership is **not** stored in the token — it is verified on every request against the database by `GatewayOwnershipFilter`. This means newly registered gateways are immediately accessible without requiring a new login.

Unowned gateway access returns **404** (not 403) to avoid revealing whether a gateway exists.

---

## Tunnel Start/Stop Flow

**Start:**
1. Query gateway summary via MQTT (to get public key)
2. Assign port from pool (`PortPoolService`)
3. Add public key + port to `tunneluser`'s `authorized_keys` (restricts key to that port)
4. Open port in Lightsail firewall
5. Send `start` command to gateway via MQTT
6. On success: persist `state=ACTIVE`, `assignedPort` to DB

**Stop:**
1. Send `stop` command to gateway via MQTT
2. On success: release port from pool, remove `authorized_keys` entry, close Lightsail firewall port
3. Kill the SSH process listening on that port (`kill -9 <pid>` via `ProcessBuilder` with `pid: host` + `CAP_KILL`) — handles cases where the gateway does not clean up correctly
4. Persist `state=STOPPED`, `assignedPort=null` to DB

---

## Local Development Setup

### Prerequisites

- Java 17
- Docker + Docker Compose

### 1. Start PostgreSQL

```bash
docker-compose up -d
```

This starts PostgreSQL on `localhost:5432` and pgAdmin on `http://localhost:5050`.

### 2. Configure

Copy `src/main/resources/application.properties.example` to `application.properties` and fill in:

```properties
# JWT
jwt.secret=your-secret-key-at-least-32-chars

# Database (matches docker-compose defaults)
spring.datasource.url=jdbc:postgresql://localhost:5432/iot_backend
spring.datasource.username=app
spring.datasource.password=secret

# AWS IoT
aws.iot.endpoint=your-endpoint.iot.region.amazonaws.com
aws.iot.clientId=iot-client-dev
aws.region=us-east-1

# SSH tunnel server
tunnel.server.host=your-server-ip
iot.instanceName=your-lightsail-instance-name
```

### 3. Run

```bash
./gradlew bootRun
```

Flyway automatically applies migrations on startup. Hibernate validates the schema against the entities after Flyway completes.

### 4. Create first user

Flyway creates the schema but not any data. Insert the first user manually:

```sql
-- Connect via pgAdmin at http://localhost:5050 or docker exec
docker exec -it iot-postgres psql -U app -d iot_backend

INSERT INTO users (username, password_hash)
VALUES ('admin@example.com', '$2a$10$YOUR_BCRYPT_HASH_HERE');
```

Generate a bcrypt hash at https://bcrypt-generator.com (rounds = 10).

---

## Production Deployment

The app and PostgreSQL both run in Docker on AWS Lightsail. Images are built locally and pushed to ECR; the server pulls and restarts.

See [ECR-Deployment.md](ECR-Deployment.md) for the full guide. Summary:

```bash
# Set once in your shell (or ~/.bashrc)
export ECR_REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com
export ECR_REPO=<your-org>/<your-app>
export AWS_REGION=<region>
export TAG=v1.01

# 1. Build and push (local machine)
docker build --no-cache --platform linux/amd64 --provenance=false -t "app:$TAG" .
docker tag "app:$TAG" "$ECR_REGISTRY/$ECR_REPO:$TAG"
docker push "$ECR_REGISTRY/$ECR_REPO:$TAG"

# 2. First deploy only — one-time server setup
./setup_server.sh "$TAG"

# 3. Deploy (every release)
./deploy_update.sh "$TAG"
```

### File layout on server

```
/home/bitnami/
├── config/
│   └── application.properties     ← production config (not in git)
├── .aws/
│   └── credentials                ← AWS credentials (not in git)
├── docker-compose.prod.yml        ← manually maintained
├── setup_server.sh                ← one-time setup (copy from repo)
└── deploy_update.sh               ← run on every release (copy from repo)
```

### Manage containers

```bash
# Status
docker ps --filter "name=iot-"

# Logs
docker logs -f iot-app

# Restart app only
docker-compose -f docker-compose.prod.yml restart app

# Full restart (including postgres)
docker-compose -f docker-compose.prod.yml down
docker-compose -f docker-compose.prod.yml up -d
```

---

## Telemetry

### DynamoDB table: `iot-telemetry`

The table must be created manually in AWS before deploying (no auto-create in code).

| Attribute | Type | Role |
|-----------|------|------|
| `gateway_id` | String | Partition key |
| `timestamp` | String (ISO-8601) | Sort key |
| `payload` | Map | Raw sensor fields from the MQTT message |
| `ttl` | Number (Unix epoch s) | TTL attribute — items expire 48 h after ingestion |

Enable TTL in the DynamoDB console on the `ttl` attribute to activate automatic hot-storage expiration.

### Query API

```
GET /api/v1/{gwId}/telemetry?from=2025-06-01T00:00:00Z&to=2025-06-01T23:59:59Z
Authorization: Bearer <token>
```

Returns a JSON array of `{ "timestamp": "...", "payload": { ...sensor fields... } }` sorted by timestamp ascending. `from`/`to` are ISO-8601 strings.

| Range | Source | Latency |
|-------|--------|---------|
| Both timestamps within last 48 h | DynamoDB | ~50 ms |
| Both timestamps older than 48 h | Athena | 2–10 s |
| Spans the 48 h boundary | Both, merged | 2–10 s |

### Cold archive setup (It3)

`ArchiveService` is activated by setting `archive.s3.bucket`. It runs daily at 2 AM UTC, reads yesterday's DynamoDB records for each gateway, writes one Parquet file per gateway to S3:

```
s3://{bucket}/{prefix}dt=yyyy-MM-dd/gateway_id={gwId}/data.parquet
```

### Athena setup (It4)

`AthenaService` is activated by setting `athena.output-location`. Create the Glue table once:

```sql
CREATE EXTERNAL TABLE mydb.telemetry_cold (
  timestamp    STRING,
  payload_json STRING)
PARTITIONED BY (dt STRING, gateway_id STRING)
STORED AS PARQUET
LOCATION 's3://your-bucket/telemetry/';
```

After each archive run, register new partitions:

```sql
MSCK REPAIR TABLE mydb.telemetry_cold;
```

Query payload fields in Athena using `json_extract`:

```sql
SELECT timestamp,
       json_extract_scalar(payload_json, '$.temp') AS temp
FROM   mydb.telemetry_cold
WHERE  gateway_id = 'gw-001'
AND    dt BETWEEN '2025-06-01' AND '2025-06-07';
```

### IAM permissions required

| Service | Actions |
|---------|---------|
| DynamoDB | `PutItem`, `Query` on `iot-telemetry` |
| S3 | `PutObject`, `GetObject`, `ListBucket` on the archive bucket |
| Athena | `StartQueryExecution`, `GetQueryExecution`, `GetQueryResults` |
| Glue | `GetTable`, `GetPartitions` on the telemetry database |
| S3 (results) | `PutObject`, `GetObject` on the Athena output location |

### AWS credentials

All AWS services (`TelemetryService`, `ArchiveService`, `AthenaService`) resolve credentials via `DefaultCredentialsProvider` — IAM role on EC2/Lightsail in production, or `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` env vars locally.

---

## Database Migrations

Migrations live in `src/main/resources/db/migration/` and run automatically on startup via Flyway.

| Version | Description |
|---------|-------------|
| V1 | Create `users` table |
| V2 | Create `gateways` table |
| V3 | Create `tunnels` table |

In production: `spring.flyway.enabled=true`, `spring.jpa.hibernate.ddl-auto=validate`.
In tests: Flyway is disabled, Hibernate uses `create-drop`.

**Never modify an existing migration.** Add a new versioned file instead.

---

## Testing

```bash
./gradlew test
```

Tests use Testcontainers 2.x — a real PostgreSQL container starts automatically. No H2, no mocks for the DB layer.

Test categories:
- **Unit tests** — `GatewayService`, `TelemetryService`, `GatewayOwnershipFilter`, etc. with Mockito
- **Controller tests** — MockMvc with `@MockitoBean` services (including `TelemetryService`)
- **Integration tests** — `JpaIntegrationTest` runs against a real PostgreSQL container

`TelemetryServiceTest` mocks `DynamoDbAsyncClient` directly — no AWS connection required.

---

## Notes

- `GatewayOwnershipFilter` checks gateway ownership on every request — one DB query per request to `/{gwId}/**`. Acceptable given the low request rate of this application.
- The MQTT `clientId` must be unique per running instance. Running two instances with the same `clientId` causes `SESSION_TAKEN_OVER` disconnections.
- `LightsailRemoteAccess` uses `ProcessBuilder` with separate arguments — no shell string concatenation, no injection risk.
- All controller methods return `CompletableFuture` to avoid blocking Tomcat threads during MQTT wait.
- Telemetry writes are fire-and-forget — a DynamoDB failure is logged but never blocks or errors the MQTT subscription.
- The `iot-telemetry` table uses string sort keys (ISO-8601) so lexicographic order equals chronological order, making range queries correct without a GSI.
- `TelemetryService` creates its own `DynamoDbAsyncClient` following the same pattern as `DynamoDBService`. Both use `DefaultCredentialsProvider`.
