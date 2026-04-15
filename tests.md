# Test Suite Reference

## Overview

73 tests across 12 test classes. Two tiers:

- **Unit tests** — no Spring context, no Docker, no external services. Instantiate classes directly. Fast, run on every build.
- **Controller / Integration tests** — full Spring Boot context with MockMvc and a real PostgreSQL container via Testcontainers. Require Docker Desktop running.

---

## Test Infrastructure

### `PostgresTestConfig`
A `@TestConfiguration` that declares a single shared `PostgreSQLContainer` (postgres:16-alpine).  
Annotated with `@ServiceConnection` — Spring Boot auto-wires the JDBC URL, username and password from the running container, no manual properties needed.  
The container is reused across all tests that share the same Spring `ApplicationContext`, so it starts only once per JVM run.

### `BaseControllerTest`
Abstract base class for all controller tests. Subclasses extend it and get:

**Spring context setup:**
- `@SpringBootTest(webEnvironment = MOCK)` — loads the full application context with a mock servlet environment.
- `@Import(PostgresTestConfig.class)` — injects the shared Postgres container.
- `@TestPropertySource` — provides all required properties so the app can start without a real `application.properties`:
  - `jwt.secret`, `jwt.expiration-ms`
  - `aws.region`, `aws.iot.endpoint`, `aws.iot.clientId`
  - `cors.allowed-origins`, `admin.api-key`
  - `tunnel.server.host`, `port.pool.start/end`
  - `iot.instanceName`
  - `spring.flyway.enabled=false`, `spring.jpa.hibernate.ddl-auto=create-drop` — schema managed by Hibernate, not Flyway, in tests.

**Mocked beans** (replaced with Mockito stubs in the Spring context so no AWS/MQTT connections are made):
| Bean | Why mocked |
|---|---|
| `GatewayService` | Business logic — configured per test |
| `MqttService` | Connects to AWS IoT Core on startup — must be suppressed |
| `PortPoolService` | Reads active tunnels from DB at startup — suppressed |
| `TelemetryService` | Connects to DynamoDB — suppressed |
| `GatewayEventBroadcaster` | SSE emitter registry — suppressed |
| `UserRepository` | Used directly by `GatewayOwnershipFilter` to resolve gateway ownership |

**Helper methods:**
- `perform(request)` — handles both sync and async controller responses. For endpoints that return `CompletableFuture`, MockMvc needs two steps: `perform()` starts the request, `asyncDispatch()` waits for the future to resolve. This method wraps both. For filter-level rejections (401, 404 from ownership) use `mockMvc.perform()` directly.
- `declareOwnership(username, ...gatewayIds)` — stubs `UserRepository.findByUsernameWithGateways()` so the `GatewayOwnershipFilter` considers a given user the owner of the listed gateways.
- `bearerToken(username, gatewayIds)` — generates a real signed JWT using the `JwtService` (loaded from context with the test secret), suitable for `Authorization: Bearer` headers.
- `mutableMap(keysAndValues...)` — creates a `HashMap` (not `Map.of`) needed when a controller mutates the returned map.

---

## Unit Tests (no Docker, no Spring)

### `GatewayServiceTest`
**Type:** Unit  
**Framework:** `@ExtendWith(MockitoExtension.class)`  
**Class under test:** `GatewayService`  
**Mocked dependencies:** `UserRepository`, `GatewayRepository`, `TunnelRepository`

| Test | What is verified |
|---|---|
| `getUserSummary` — returns map with username | `findByUsernameWithGateways` called; result contains `username` key |
| `getUserSummary` — throws when user not found | `ResourceNotFoundException` with username in message |
| `getTunnelList` — returns map of tunnel fields | `findAllByGatewayId` called; result contains name and src_port |
| `getTunnelList` — throws when gateway not found | `ResourceNotFoundException` with gateway ID |
| `createTunnel` — saves and returns generated ID | `tunnelRepository.save()` called; returned ID is non-blank |
| `createTunnel` — throws when gateway not found | `ResourceNotFoundException` |
| `updateTunnel` — mutates entity fields | Entity fields updated via setters; `save()` is NOT called (relies on Hibernate dirty checking) |
| `markTunnelActive` — sets state and port | `TunnelState.ACTIVE` and `assignedPort` set correctly |
| `markTunnelStopped` — clears port and state | `TunnelState.STOPPED` and `assignedPort = null` |

**Key design note:** `updateTunnel` verifies that `tunnelRepository.save()` is never called — the design relies on Hibernate dirty-checking to flush the mutation automatically within the transaction.

---

### `TelemetryServiceTest`
**Type:** Unit  
**Framework:** `@ExtendWith(MockitoExtension.class)` (manual setup in `@BeforeEach`)  
**Class under test:** `TelemetryService`  
**Mocked dependencies:** `DynamoDbAsyncClient`

| Test group | Test | What is verified |
|---|---|---|
| `save` | calls putItem with correct attributes | `tableName=iot-telemetry`, `gateway_id`, `timestamp`, `payload` present; TTL is within ±1h of now+48h |
| `save` | propagates DynamoDB errors | `CompletableFuture` fails with the original `ResourceNotFoundException` |
| `query` | returns list of timestamp+payload maps | Result has 1 entry; `timestamp` and `payload` keys present |
| `query` | uses correct key condition | `tableName=iot-telemetry`; condition contains `BETWEEN`; `#ts` expression name present; `:gwId` = `gw-001` |
| `query` | returns empty list when no items | Empty result, no error |

---

### `ArchiveServiceTest`
**Type:** Unit  
**Framework:** `@ExtendWith(MockitoExtension.class)`  
**Class under test:** `ArchiveService`  
**Mocked dependencies:** `TelemetryService`, `GatewayRepository`, `S3Client`  
**Constructed with:** `bucket=my-bucket`, `prefix=telemetry/`

| Test group | Test | What is verified |
|---|---|---|
| `archiveGatewayDay` | skips S3 upload when DynamoDB has no records | Returns 0; `s3.putObject` never called |
| `archiveGatewayDay` | uploads Parquet with Hive-partition key | Returns 1; S3 key = `telemetry/dt=2025-06-01/gateway_id=gw-001/data.parquet` |
| `archiveGatewayDay` | queries DynamoDB with full-day UTC range | `queryRawPaginated` called with `2025-06-01T00:00:00Z` → `2025-06-02T00:00:00Z` |
| `archiveYesterday` | a failure on one gateway does not stop others | Two gateways; gw-001 fails, gw-002 completes; no exception thrown; both were attempted |

---

### `AthenaServiceTest`
**Type:** Unit  
**Framework:** `@ExtendWith(MockitoExtension.class)`  
**Class under test:** `AthenaService`  
**Mocked dependencies:** `AthenaAsyncClient`  
**Constructed with:** `database=mydb`, `table=telemetry_cold`, `outputLocation=s3://bucket/athena-results/`

The tests use private stub helpers (`stubStart`, `stubStatus`, `stubStatusFailed`, `stubResults`) that chain the three Athena API calls the service makes: `startQueryExecution` → `getQueryExecution` (polling) → `getQueryResults`.

| Test | What is verified |
|---|---|
| returns parsed rows on success | Result has 1 row; `timestamp` and `payload` (parsed JSON `Map`) correct |
| returns empty list when only header row | Empty result, no error |
| fails future when query status is FAILED | `CompletableFuture` fails; cause message contains Athena error reason |
| rejects gateway IDs with special characters | `IllegalArgumentException` before any API call (SQL injection guard) |

---

### `TelemetryAggregatorTest`
**Type:** Unit (pure — no mocks, no I/O)  
**Class under test:** `TelemetryAggregator` (static utility)

Input format for each point: `{ "timestamp": ISO-8601, "payload": { fieldName: Number, ... } }`

| Test | What is verified |
|---|---|
| empty input | Returns empty list |
| bucketing | Two points in hour-0, one in hour-1 → 2 buckets with counts 2 and 1 |
| AVG | Average per field across bucket |
| MIN | Minimum per field |
| MAX | Maximum per field |
| SUM | Sum per field |
| COUNT | Count per bucket; values map is empty (no per-field data for COUNT) |
| non-numeric fields ignored | String field `"status"` excluded from output; numeric field kept |
| `parseWindow` — valid sizes | 1m, 5m, 15m, 30m, 1h, 6h, 12h, 1d all parse correctly |
| `parseWindow` — invalid | `IllegalArgumentException` for unsupported value (e.g. `"2h"`) |
| `parseFn` — defaults to AVG | `null` or empty string → `Fn.AVG` |
| `parseFn` — invalid | `IllegalArgumentException` for unsupported function (e.g. `"median"`) |

---

### `GatewayEventBroadcasterTest`
**Type:** Unit (no mocks — instantiates `GatewayEventBroadcaster` directly)  
**Class under test:** `GatewayEventBroadcaster`

| Test | What is verified |
|---|---|
| `subscribe` returns an `SseEmitter` | Return value is non-null |
| `broadcast` sends without throwing | No exception escapes (outside a servlet context `SseEmitter.send()` throws; broadcaster catches and removes emitter) |
| `broadcast` to gateway with no subscribers | No-op, no exception |
| failed send removes emitter | After a failed broadcast the emitter is removed; second broadcast is a no-op |
| multiple subscribers all receive broadcast | Two distinct emitters returned; both fail cleanly outside servlet context |

**Note:** These tests cannot verify that data actually reaches a client because `SseEmitter.send()` requires an active servlet context. What they verify is the registry management (subscribe, remove on failure) and that no exception escapes the broadcaster.

---

## Controller Tests (requires Docker)

All controller tests extend `BaseControllerTest`. The Spring context starts once and is shared. Docker must be running for Testcontainers to start the PostgreSQL container.

### `LoginControllerTest`
**Endpoints:** `POST /auth/login`, `POST /auth/register`

| Test | Setup | Expected |
|---|---|---|
| valid credentials returns 200 + JWT | `getUserWithGateways("alice")` returns user with BCrypt hash matching `"secret123"` | 200, `$.token` non-empty, `$.status = "ok"` |
| wrong password returns 401 | Same user but submitted password is wrong | 401 |
| unknown user returns 404 | `getUserWithGateways("nobody")` throws `ResourceNotFoundException` | 404 |
| missing body returns 5xx | No JSON body | 5xx (Spring deserialization error) |
| register with valid admin key returns 201 | `X-Admin-Key: test-admin-key` header; `registerUser` is no-op | 201, `$.username = "newuser"` |
| register duplicate returns 409 | `registerUser` throws `ConflictException` | 409, `$.error = "CONFLICT"` |
| register without admin key returns 403 | No `X-Admin-Key` header | 403 |

**Note:** Login is a synchronous endpoint; uses `perform()` (with asyncDispatch). Register does not return a `CompletableFuture`; uses `mockMvc.perform()` directly.

---

### `TunnelControllerTest`
**Endpoints:** `GET/POST/PUT/DELETE /api/v1/{gwId}/tunnels[/{tunnelId}]`  
**Auth setup:** `declareOwnership("alice", "gw-001")` + bearer token in every request.

| Test | Mocked behavior | Expected |
|---|---|---|
| `GET /tunnels` returns list | `getTunnelList` returns map with one entry | 200, `$.tunnel-abc.name = "my-tunnel"` |
| `GET /tunnels` — gateway not found | `getTunnelList` throws `ResourceNotFoundException` | 404, `$.error = "NOT_FOUND"` |
| `GET /tunnels/{id}` returns tunnel | `getTunnelDetail` returns field map | 200, correct fields |
| `GET /tunnels/{id}` — not found | `getTunnelDetail` throws `ResourceNotFoundException` | 404 |
| `POST /tunnels` creates tunnel | `createTunnel` returns `"generated-tunnel-id"` | 201, `$.tunnelId` non-empty |
| `PUT /tunnels/{id}` updates | `updateTunnel` is no-op | 204 |
| `PUT /tunnels/{id}` — not found | `updateTunnel` throws `ResourceNotFoundException` | 404 |
| `DELETE /tunnels/{id}` deletes | `getTunnelDetail` returns config; `sendAsync` succeeds; `deleteTunnel` is no-op | 204; `deleteTunnel` verified called |
| `DELETE /tunnels/{id}` — MQTT fails but deletes | `sendAsync` returns failed future | 204; `deleteTunnel` still called |
| `DELETE /tunnels/{id}` — tunnel not found | `getTunnelDetail` throws `ResourceNotFoundException` | 404; `deleteTunnel` never called |

---

### `GatewayControllerTest`
**Endpoint:** `POST /api/v1/gateways`  
**Auth setup:** `declareOwnership("alice")` (no gateways yet) + bearer token.  
This endpoint is not under `GatewayOwnershipFilter` because `"gateways"` is in `NON_GATEWAY_SEGMENTS`.

| Test | Mocked behavior | Expected |
|---|---|---|
| registers gateway returns 201 | `registerGateway("alice", req)` returns `"gw-new-001"` | 201, `$.gateway_id = "gw-new-001"` |
| conflict returns 409 | `registerGateway` throws `ConflictException` | 409, `$.error = "CONFLICT"` |
| no token returns 401 | No auth header | 401 |

---

### `UserControllerTest`
**Endpoint:** `POST /api/v1/user/password`  
**Auth setup:** `declareOwnership("alice")` + bearer token.  
This endpoint is not under `GatewayOwnershipFilter` because `"user"` is in `NON_GATEWAY_SEGMENTS`.

| Test | Mocked behavior | Expected |
|---|---|---|
| valid password change returns 204 | `changePassword` is no-op | 204 |
| wrong current password returns 401 | `changePassword` throws `ResponseStatusException(401)` | 401 |
| no token returns 401 | No auth header | 401 |

---

### `GatewayOwnershipFilterTest`
**Purpose:** Tests the `GatewayOwnershipFilter` security boundary in isolation, not a specific controller's business logic.

The filter runs on every request to `/api/v1/{segment}/...` where `{segment}` is not in `NON_GATEWAY_SEGMENTS` (like `summary`, `gateways`, `user`). It resolves the authenticated user from the JWT, loads their gateways from `UserRepository`, and rejects with 404 if the gateway in the URL is not theirs.

| Test | Setup | Expected |
|---|---|---|
| owned gateway allows request | `declareOwnership("alice", "gw-owned")`; token for alice; `getTunnelList` returns empty | 200 |
| unowned gateway returns 404 | `declareOwnership("alice", "gw-owned")`; request to `/gw-someone-elses/tunnels` | 404 (filter rejects synchronously — uses `mockMvc.perform()` directly, not `perform()`) |
| no token returns 401 | No auth header | 401 |
| `/summary` route passes filter | Request to `/api/v1/summary` with valid token | 200 (filter skips non-gateway segments) |

---

## Integration Tests (requires Docker)

### `JpaIntegrationTest`
**Type:** Integration — real Spring context, real Hibernate, real PostgreSQL via Testcontainers.  
**Purpose:** Verifies JPA mappings, custom queries, and cascade behavior against an actual database.  
**Schema:** Created by `spring.jpa.hibernate.ddl-auto=create-drop`. Flyway is disabled.  
**Mocked:** Only `MqttService` (prevents AWS connection at startup).

| Test | What is verified |
|---|---|
| saves and retrieves user by username | Basic `save` + `findByUsername` round-trip |
| `findByUsernameWithGateways` loads gateways in single query | No N+1: user + 2 gateways loaded; confirms `JOIN FETCH` in the JPQL query |
| `findAllByOwnerUsernameWithTunnels` loads tunnels eagerly | Gateway + 2 tunnels loaded in one query |
| `findByIdAndGatewayId` rejects tunnel from different gateway | Security: looking up a tunnel by its ID using a different gateway's ID returns `Optional.empty()` |
| deleting gateway cascades to tunnels | After `gatewayRepository.delete(gw)`, associated tunnels are gone |

---

### `CloudApplicationTests`
**Type:** Smoke test — Spring context only.  
**Purpose:** Verifies the entire application context loads without errors.  
**Mocked:** `MqttService` (prevents AWS connection).  
**Tests:** Single `contextLoads()` — passes if no exception is thrown during context startup.

---

## What is NOT tested

| Area | Gap |
|---|---|
| `TelemetryController` | The hot/cold/merged routing logic (most complex branch in the codebase) has no tests |
| `MqttService` | No tests: disconnect/reconnect/resubscribe flow, message routing (response vs status vs event), `sendAsync` timeout path |
| `TunnelController` start/stop | The MQTT command flow for starting and stopping tunnels is not covered |
| `PortPoolService` reconciliation | The `ApplicationReadyEvent` startup logic that scans active tunnels and pre-marks ports is not covered |
| `EventController` (SSE) | Subscription endpoint and broadcast-to-client flow are not covered |
| `AdminController` | Beyond what `LoginControllerTest` covers at the filter level |
