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
- Each gateway gets one line in `/home/tunneluser/.ssh/authorized_keys` with a `permitlisten` restriction — OpenSSH enforces at protocol level that each key can only open its assigned port

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
│   ├── OpenApiConfig.java         # Swagger/OpenAPI setup
│   └── PortPoolConfig.java        # Port range bean
├── exception/
│   └── GlobalExceptionHandler.java
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
    ├── PortPool.java              # Thread-safe in-memory port pool
    └── LightsailRemoteAccess.java # Host-level SSH and firewall ops
```

---

## Configuration (`application.properties`)

Copy `application.properties.example` to `application.properties` and fill in all values.

```properties
# JWT
jwt.secret=<min 32-char secret>
jwt.expiration-ms=86400000

# CORS — comma-separated list of allowed origins
cors.allowed-origins=http://localhost:5173

# Port pool range (in-memory, assigned to SSH tunnels)
port.pool.start=9000
port.pool.end=10000

# SSH tunnel server hostname (returned to gateways on tunnel start)
tunnel.server.host=<your-server-hostname>

# AWS region
aws.region=us-east-1

# AWS IoT MQTT
aws.iot.endpoint=<your-endpoint>.iot.<region>.amazonaws.com
aws.iot.clientId=cloud-backend

# AWS Lightsail instance name (for firewall rule management)
iot.instanceName=<your-lightsail-instance-name>

# OS user that owns the SSH authorized_keys file (default: tunneluser)
ssh.tunnel.user=tunneluser
```

AWS credentials are resolved via the default SDK credential chain (IAM role, `~/.aws/credentials`, env vars).

---

## Server Setup (one-time)

### 1. Create the tunnel user

```bash
sudo useradd -m -s /usr/sbin/nologin tunneluser
sudo passwd -l tunneluser
sudo mkdir -p /home/tunneluser/.ssh
sudo touch /home/tunneluser/.ssh/authorized_keys
sudo chown -R tunneluser:tunneluser /home/tunneluser/.ssh
sudo chmod 700 /home/tunneluser/.ssh
sudo chmod 600 /home/tunneluser/.ssh/authorized_keys
```

### 2. Configure sshd_config

Add the following block at the end of `/etc/ssh/sshd_config` (replace `tunneluser` if you changed `ssh.tunnel.user`):

```
Match User tunneluser
    AllowTcpForwarding yes
    GatewayPorts clientspecified
    PermitTTY no
    ForceCommand echo 'Tunnel only'
    X11Forwarding no
```

Then reload sshd:

```bash
sudo systemctl reload ssh
```

This block is static — it never needs to change. All dynamic configuration (which gateway can use which port) lives in `authorized_keys` and is managed by the application.

### 3. Configure sudoers

The application needs to read and write `authorized_keys` without a password prompt. Replace `ubuntu` with the OS user running the Java process:

```bash
sudo visudo -f /etc/sudoers.d/cloud-app
```

```
ubuntu ALL=(ALL) NOPASSWD: /usr/bin/tee /home/tunneluser/.ssh/authorized_keys
ubuntu ALL=(ALL) NOPASSWD: /usr/bin/cat /home/tunneluser/.ssh/authorized_keys
ubuntu ALL=(ALL) NOPASSWD: /usr/bin/lsof -P -i -n
ubuntu ALL=(ALL) NOPASSWD: /usr/bin/kill -9 *
ubuntu ALL=(ALL) NOPASSWD: /usr/bin/aws lightsail *
```

### How authorized_keys works

Each gateway that has an active tunnel gets one line:

```
restrict,port-forwarding,permitlisten="0.0.0.0:9001" ssh-ed25519 AAAA... gw-001
restrict,port-forwarding,permitlisten="0.0.0.0:9002" ssh-ed25519 AAAA... gw-002
```

OpenSSH enforces at protocol level that `gw-001` can only open port `9001` — no extra validation needed in Java. Lines are added on `tunnel start` and removed on `tunnel stop` or `tunnel delete`.

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
| `POST` | `/api/v1/{gwId}/tunnels/{tunnelId}/start` | Start tunnel (assigns port, updates SSH keys, opens firewall) |
| `POST` | `/api/v1/{gwId}/tunnels/{tunnelId}/stop` | Stop tunnel (releases port, removes SSH key, closes firewall) |

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

> When `use_this_server` is `"on"`, the server assigns a port from the pool, adds a `permitlisten`-restricted line to `authorized_keys`, and opens the Lightsail firewall rule. When `"off"`, the gateway manages its own tunnel endpoint.

---

### Generic Gateway Proxy

```
GET|POST|PUT|DELETE /api/v1/{gwId}/proxy/{*path}
```

Forwards any request to a gateway via MQTT and returns its response. Body is forwarded as the `command` field.

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
| `pubkey` | String | SSH public key of the gateway (ed25519 recommended) |
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

Swagger UI: `http://localhost:8080/swagger-ui/index.html`

---

## Security Notes

- CORS origins are configured via `cors.allowed-origins` — comma-separated, e.g. `http://localhost:5173,https://app.example.com`
- The `GatewayOwnershipFilter` returns **404** (not 403) on ownership mismatch to prevent gateway ID enumeration
- JWT tokens expire after the duration set in `jwt.expiration-ms` (default 24h)
- Port pool state is **in-memory only** — restarting the server resets it. Gateways with active tunnels will need to reconnect, which is idempotent (calling `start` on an already-started tunnel is safe)
- Shell commands on the host use `ProcessBuilder` with separated arguments — no shell injection risk regardless of gateway ID or pubkey content
