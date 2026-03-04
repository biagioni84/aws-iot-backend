# cloud — IoT Gateway Management API

A Spring Boot backend that manages IoT gateways via MQTT over AWS IoT Core. Exposes a REST API for user authentication, gateway monitoring, and SSH tunnel lifecycle management. Designed to run on an AWS Lightsail instance.

---

## Architecture Overview

```
Client (React / CLI)
        │  JWT (Bearer)
        ▼
Spring Boot API  ──MQTT──▶  AWS IoT Core  ──▶  IoT Gateways
        │
        ├── DynamoDB (users, gateway metadata, tunnel config)
        └── Lightsail OS (SSH authorized_keys, firewall rules, port pool)
```

**Key design decisions:**
- All gateway communication is async over MQTT (request/response pattern with correlation IDs)
- JWT tokens embed the list of gateway IDs the user owns — no DB lookup on every request
- A `GatewayOwnershipFilter` validates ownership on every `/api/v1/{gwId}/**` call, returning 404 (not 403) to avoid gateway enumeration
- Port pool is held in-memory and managed by `PortPoolService` + `LightsailRemoteAccess`

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.0.2, Java 17 |
| Security | Spring Security + JWT (jjwt 0.11.5) |
| Cloud | AWS DynamoDB (async SDK 2.x), AWS IoT (MQTT5) |
| Infra | AWS Lightsail (SSH tunnel server) |
| Docs | SpringDoc OpenAPI / Swagger UI |
| Build | Gradle |

---

## Project Structure

```
src/main/java/uy/plomo/cloud/
├── CloudApplication.java          # Entry point
├── config/
│   ├── SecurityConfig.java        # JWT filter chain, CORS
│   ├── GlobalExceptionHandler.java
│   ├── OpenApiConfig.java         # Swagger/OpenAPI setup
│   ├── PortPoolConfig.java        # Port range bean
│   └── WebConfig.java
├── controllers/
│   ├── LoginController.java       # POST /auth/login → JWT
│   ├── AdminController.java       # GET /api/v1/summary, /gateways
│   ├── GatewayController.java     # Generic MQTT proxy
│   └── TunnelController.java      # Tunnel CRUD + start/stop
├── security/
│   ├── JwtService.java            # Token generation & validation
│   ├── JwtAuthenticationFilter.java
│   └── GatewayOwnershipFilter.java
├── services/
│   ├── DynamoDBService.java       # All DynamoDB operations
│   ├── MqttService.java           # MQTT5 client, pub/sub
│   ├── PendingRequestsService.java # Request correlation map
│   └── PortPoolService.java       # Port assignment + SSH key mgmt
└── platform/
    ├── PortPool.java              # Thread-safe port pool
    └── LightsailRemoteAccess.java # Shell commands on the host
```

---

## API Endpoints

All authenticated endpoints require `Authorization: Bearer <token>`.

### Authentication

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/login` | Returns a JWT token |

**Request body:**
```json
{ "username": "alice", "password": "secret" }
```

**Response:**
```json
{ "token": "eyJ...", "status": "ok" }
```

---

### Admin / Info

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/summary` | User profile + gateway metadata + active SSH tunnels |
| `GET` | `/api/v1/gateways` | Live status from all gateways via MQTT |

---

### Tunnels

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/{gwId}/tunnels` | List tunnels for a gateway |
| `GET` | `/api/v1/{gwId}/tunnels/{tunnelId}` | Get tunnel details |
| `POST` | `/api/v1/{gwId}/tunnels` | Create new tunnel (UUID assigned by server) |
| `PUT` | `/api/v1/{gwId}/tunnels/{tunnelId}` | Update tunnel config |
| `DELETE` | `/api/v1/{gwId}/tunnels/{tunnelId}` | Stop + delete tunnel |
| `POST` | `/api/v1/{gwId}/tunnels/{tunnelId}/start` | Start tunnel (assigns port, updates SSH keys) |
| `POST` | `/api/v1/{gwId}/tunnels/{tunnelId}/stop` | Stop tunnel (releases port) |

**Tunnel request body (create / update):**
```json
{
  "name": "my-tunnel",
  "src_addr": "localhost",
  "src_port": "8080",
  "dst_port": "9001",
  "use_this_server": "on"
}
```

> When `use_this_server` is `"on"`, the server assigns a port from the pool, registers an SSH authorized key for the gateway, and opens the Lightsail firewall rule automatically.

---

### Generic Gateway Proxy

```
GET|POST|PUT|DELETE /api/v1/{gwId}/proxy/{*path}
```

Forwards any request to a gateway via MQTT and returns its response. Body is forwarded as the `command` field.

---

## Configuration (`application.properties`)

```properties
# JWT
jwt.secret=<min 32-char secret>

# Port pool range (in-memory)
port.pool.start=9000
port.pool.end=10000

# SSH tunnel server hostname
tunnel.server.host=<your-server-hostname>

# AWS IoT MQTT
aws.iot.endpoint=<your-endpoint>.iot.<region>.amazonaws.com
aws.iot.clientId=cloud-backend

# AWS Lightsail instance name (for firewall management)
iot.instanceName=<your-lightsail-instance-name>
```

AWS credentials are resolved via the default SDK credential chain (IAM role, `~/.aws/credentials`, env vars).

---

## DynamoDB Schema

**Table: `users`**
| Attribute | Type | Notes |
|---|---|---|
| `username` | String (PK) | |
| `password` | String | bcrypt hash |
| `gateways` | List\<String\> | gateway IDs owned by the user |

**Table: `iot-gateways`**
| Attribute | Type | Notes |
|---|---|---|
| `gateway_id` | String (PK) | |
| `pubkey` | String | SSH public key of the gateway |
| `tunnels` | Map | Map of `tunnelId → TunnelConfig` |

Each tunnel entry in the map:
```json
{
  "name": "my-tunnel",
  "src_addr": "localhost",
  "src_port": "8080",
  "dst_port": "9001",
  "use_this_server": "on"
}
```

---

## MQTT Protocol

Topics follow the namespace `iot/v1`:

| Direction | Topic pattern |
|---|---|
| Cloud → Gateway (command) | `iot/v1/{gwId}/request/{requestId}` |
| Gateway → Cloud (response) | `iot/v1/{gwId}/response/{requestId}` |
| Gateway → Cloud (events) | `iot/v1/{gwId}/event/#` |
| Gateway → Cloud (status) | `iot/v1/{gwId}/status` |

**Command payload:**
```json
{ "path": "GET:/summary", "command": {} }
```

Requests time out after **30 seconds**. All pending requests are failed immediately on MQTT disconnection.

---

## Building

```bash
# Build the lean JAR + copy dependencies
./gradlew build

# Only rebuild your code (fast iteration)
./gradlew updateApp
```

Output: `build/libs/app.jar` + `build/libs/lib/` (dependencies)

---

## Running

```bash
java -jar build/libs/app.jar \
  --jwt.secret=<secret> \
  --aws.iot.endpoint=<endpoint> \
  --tunnel.server.host=<host>
```

Swagger UI is available at: `http://localhost:8080/swagger-ui/index.html`

---

## Security Notes

- CORS is currently restricted to `http://localhost:5173` — update `SecurityConfig` for production.
- The `GatewayOwnershipFilter` returns **404** (not 403) on ownership mismatch to prevent gateway ID enumeration.
- JWT tokens expire after **24 hours**.
- Port pool state is **in-memory only** — restarting the server will reset it. Tunnels that were active before a restart will need to be stopped and restarted manually.
