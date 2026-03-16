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
    ├── TelemetryController       — query historical sensor data
    └── LoginController           — authentication
         │
         ├── GatewayService       — synchronous JPA service
         ├── MqttService          — MQTT5 pub/sub, request/response + telemetry ingest
         ├── TelemetryService     — DynamoDB persistence for sensor data
         ├── PortPoolService      — SSH port assignment
         └── LightsailRemoteAccess — authorized_keys + Lightsail firewall management
              │
              ├── PostgreSQL (via Docker)
              ├── AWS IoT Core (MQTT)
              └── AWS DynamoDB (iot-telemetry table)
```

### MQTT Request/Response Pattern

Each command to a gateway is sent to `iot/v1/{gatewayId}/request/{correlationId}` and awaits a response on `iot/v1/{gatewayId}/response/{correlationId}`. Pending requests are tracked with `CompletableFuture` and timed out automatically.

### Telemetry Ingest Pipeline

```
Gateway → MQTT iot/v1/{gatewayId}/status → MqttService → TelemetryService → DynamoDB iot-telemetry
```

Each message on `iot/v1/{gatewayId}/status` (~3 KB JSON, ~1 msg/min/gateway) is persisted as a single DynamoDB item. The write is fire-and-forget — errors are logged but never propagate back to the MQTT thread.

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
| GET | `/api/v1/{gwId}/telemetry?from=&to=` | Query sensor telemetry for a time range |

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
3. Kill the SSH process listening on that port (`sudo kill -9 <pid>`) — handles cases where the gateway does not clean up correctly
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

The app runs as a `systemd` service. PostgreSQL runs in Docker.

### File layout on server

```
/home/bitnami/
├── app.jar                    ← lean jar (no dependencies)
├── lib/                       ← runtime dependencies
├── application.properties     ← production config (not in git)
└── docker-compose.prod.yml
```

### Start PostgreSQL

```bash
docker-compose -f docker-compose.prod.yml up -d
```

### Manage the service

```bash
sudo systemctl start iot-backend
sudo systemctl stop iot-backend
sudo systemctl restart iot-backend
journalctl -u iot-backend.service -f
```

### Deploy a new version

```bash
# On dev machine
./gradlew build

# Upload
scp build/libs/app.jar build/libs/lib/ user@server:/home/bitnami/

# On server
sudo systemctl restart iot-backend
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

Returns a JSON array of `{ "timestamp": "...", "payload": { ...sensor fields... } }` objects sorted by timestamp ascending.

Both `from` and `to` are ISO-8601 strings and map directly to DynamoDB sort key range (`BETWEEN`).

### AWS credentials

`TelemetryService` resolves credentials via `DefaultCredentialsProvider` (IAM role on EC2/Lightsail, or `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` env vars locally). Region is read from `aws.region` in `application.properties`.

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
