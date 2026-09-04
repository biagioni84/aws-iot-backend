# Project Class Map
Generated: 2026-09-04 20:13

> Read this before searching src/ broadly. Use paths here to navigate directly.

## REST API Endpoints

- `GET /api/v1/summary` -> AdminController
- `GET /api/v1/gateways` -> AdminController
- `GET /api/v1/{gwId}/events` -> EventController
- `POST /api/v1/gateways` -> GatewayController
- `ALL /api/v1/{gwId}/proxy/{*path}` -> GatewayController
- `POST /auth/register` -> LoginController
- `POST /auth/login` -> LoginController
- `GET /api/v1/{gwId}/telemetry` -> TelemetryController
- `GET /api/v1/{gwId}/telemetry/aggregate` -> TelemetryController
- `GET /api/v1/{gwId}/tunnels` -> TunnelController
- `GET /api/v1/{gwId}/tunnels/{tunnelId}` -> TunnelController
- `POST /api/v1/{gwId}/tunnels` -> TunnelController
- `PUT /api/v1/{gwId}/tunnels/{tunnelId}` -> TunnelController
- `DELETE /api/v1/{gwId}/tunnels/{tunnelId}` -> TunnelController
- `POST /api/v1/{gwId}/tunnels/{tunnelId}/start` -> TunnelController
- `POST /api/v1/{gwId}/tunnels/{tunnelId}/stop` -> TunnelController
- `POST /api/v1/user/password` -> UserController

## Service Dependency Graph

- LightsailRemoteAccess -> LightsailClient
- ArchiveService -> GatewayRepository, S3Client, TelemetryService
- AthenaService -> AthenaAsyncClient
- GatewayService -> GatewayRepository, PasswordEncoder, TunnelRepository, UserRepository
- MqttService -> GatewayEventBroadcaster, PendingRequestsService, TelemetryService
- PortPoolService -> LightsailRemoteAccess, PortPool, TunnelRepository
- TelemetryService -> DynamoDbAsyncClient

## Flyway Migrations

- V1: create users (`V1__create_users.sql`)
- V2: create gateways (`V2__create_gateways.sql`)
- V3: create tunnels (`V3__create_tunnels.sql`)

## Configuration Properties (@Value)

- `admin.api-key`
- `archive.s3.bucket`
- `archive.s3.prefix` *(default: `telemetry/`)*
- `athena.database`
- `athena.output-location`
- `athena.table` *(default: `telemetry_cold`)*
- `aws.iot.clientId`
- `aws.iot.endpoint`
- `aws.region`
- `cors.allowed-origins`
- `iot.instanceName`
- `jwt.expiration-ms` *(default: `86400000`)*
- `jwt.secret`
- `port.pool.end` *(default: `10000`)*
- `port.pool.start` *(default: `9000`)*
- `ssh.tunnel.user` *(default: `tunneluser`)*
- `tunnel.server.host`

## Conditional Services (@ConditionalOnProperty)

- ArchiveService requires: `archive.s3.bucket`
- AthenaService requires: `athena.output-location`

## Class Map

### uy.plomo.cloud

**class CloudApplication**
Path: `src/main/java/uy/plomo/cloud/CloudApplication.java`
Methods: `main()`

### uy.plomo.cloud.config

**class FlywayConfig** [Configuration] implements BeanFactoryPostProcessor
Path: `src/main/java/uy/plomo/cloud/config/FlywayConfig.java`
Methods: `postProcessBeanFactory()`, `flyway()`

**class OpenApiConfig** [Configuration]
Path: `src/main/java/uy/plomo/cloud/config/OpenApiConfig.java`
Methods: `customOpenAPI()`

**class PortPoolConfig** [Configuration]
Path: `src/main/java/uy/plomo/cloud/config/PortPoolConfig.java`
Methods: `portPool()`

**class SecurityConfig** [Configuration]
Path: `src/main/java/uy/plomo/cloud/config/SecurityConfig.java`
Deps: AdminApiKeyFilter, GatewayOwnershipFilter, JwtAuthenticationFilter
Methods: `passwordEncoder()`, `securityFilterChain()`

### uy.plomo.cloud.controllers

**class AdminController** [RestController]
Path: `src/main/java/uy/plomo/cloud/controllers/AdminController.java`
Routes: `GET /api/v1/summary`, `GET /api/v1/gateways`
Deps: GatewayService, LightsailRemoteAccess, MqttService
Methods: `summary()`, `gateways()`

**class EventController** [RestController]
Path: `src/main/java/uy/plomo/cloud/controllers/EventController.java`
Routes: `GET /api/v1/{gwId}/events`
Deps: GatewayEventBroadcaster
Methods: `subscribe()`

**class GatewayController** [RestController]
Path: `src/main/java/uy/plomo/cloud/controllers/GatewayController.java`
Routes: `POST /api/v1/gateways`, `ALL /api/v1/{gwId}/proxy/{*path}`
Deps: GatewayService, MqttService
Methods: `registerGateway()`, `proxy()`

**class LoginController** [RestController]
Path: `src/main/java/uy/plomo/cloud/controllers/LoginController.java`
Routes: `POST /auth/register`, `POST /auth/login`
Deps: GatewayService, JwtService, PasswordEncoder
Methods: `register()`, `login()`

**class TelemetryController** [RestController]
Path: `src/main/java/uy/plomo/cloud/controllers/TelemetryController.java`
Routes: `GET /api/v1/{gwId}/telemetry`, `GET /api/v1/{gwId}/telemetry/aggregate`
Deps: TelemetryService
Methods: `getTelemetry()`, `getAggregate()`

**class TunnelController** [RestController]
Path: `src/main/java/uy/plomo/cloud/controllers/TunnelController.java`
Routes: `GET /api/v1/{gwId}/tunnels`, `GET /api/v1/{gwId}/tunnels/{tunnelId}`, `POST /api/v1/{gwId}/tunnels`, `PUT /api/v1/{gwId}/tunnels/{tunnelId}`, `DELETE /api/v1/{gwId}/tunnels/{tunnelId}`, `POST /api/v1/{gwId}/tunnels/{tunnelId}/start`, `POST /api/v1/{gwId}/tunnels/{tunnelId}/stop`
Deps: GatewayService, LightsailRemoteAccess, MqttService, PortPoolService
Methods: `tunnelList()`, `tunnelDetail()`, `newTunnel()`, `updateTunnel()`, `deleteTunnel()`, `tunnelStart()`, `tunnelStop()`

**class UserController** [RestController]
Path: `src/main/java/uy/plomo/cloud/controllers/UserController.java`
Routes: `POST /api/v1/user/password`
Deps: GatewayService
Methods: `changePassword()`

### uy.plomo.cloud.dto

**record GatewayRegistrationRequest**
Path: `src/main/java/uy/plomo/cloud/dto/GatewayRegistrationRequest.java`

**record TunnelRequest**
Path: `src/main/java/uy/plomo/cloud/dto/TunnelRequest.java`
Methods: `usesThisServer()`

### uy.plomo.cloud.entity

**class Gateway** [Entity, Table]
Path: `src/main/java/uy/plomo/cloud/entity/Gateway.java`
Methods: `create()`, `getId()`, `getPublicKey()`, `getStatus()`, `getOwner()`, `getTunnels()`, `setStatus()`

**enum GatewayStatus**
Path: `src/main/java/uy/plomo/cloud/entity/GatewayStatus.java`

**class Tunnel** [Entity, Table]
Path: `src/main/java/uy/plomo/cloud/entity/Tunnel.java`
Methods: `create()`, `update()`, `getId()`, `getName()`, `getSrcAddr()`, `getSrcPort()`, `getDstPort()`, `isUseThisServer()`, `getState()`, `getAssignedPort()`

**enum TunnelState**
Path: `src/main/java/uy/plomo/cloud/entity/TunnelState.java`

**class User** [Entity, Table]
Path: `src/main/java/uy/plomo/cloud/entity/User.java`
Methods: `create()`, `getId()`, `getUsername()`, `getPasswordHash()`, `getGateways()`, `setPasswordHash()`

### uy.plomo.cloud.exception

**class GlobalExceptionHandler** [RestControllerAdvice]
Path: `src/main/java/uy/plomo/cloud/exception/GlobalExceptionHandler.java`
Methods: `handleGwNotFound()`, `handleGwConflict()`, `handleCompletion()`, `handleTimeout()`, `handleResponseStatus()`, `handleGeneric()`

### uy.plomo.cloud.platform

**class LightsailRemoteAccess** [Service]
Path: `src/main/java/uy/plomo/cloud/platform/LightsailRemoteAccess.java`
Deps: LightsailClient
Methods: `addGatewayKey()`, `removeGatewayKeyByPort()`, `addInboundRule()`, `removeInboundRule()`, `listSshConnections()`, `listShConnected()`, `getTunnelData()`, `killSshTunnelByPort()`, `killSshTunnel()`, `killProcess()`

**class PortPool** [Component]
Path: `src/main/java/uy/plomo/cloud/platform/PortPool.java`
Methods: `acquirePort()`, `releasePort()`, `getPortEntry()`, `getAllPorts()`, `getUsedPorts()`, `getFreePorts()`

### uy.plomo.cloud.security

**class AdminApiKeyFilter** [Component] extends OncePerRequestFilter
Path: `src/main/java/uy/plomo/cloud/security/AdminApiKeyFilter.java`

**class GatewayOwnershipFilter** [Component] extends OncePerRequestFilter
Path: `src/main/java/uy/plomo/cloud/security/GatewayOwnershipFilter.java`
Deps: UserRepository

**class JwtAuthenticationFilter** [Component] extends OncePerRequestFilter
Path: `src/main/java/uy/plomo/cloud/security/JwtAuthenticationFilter.java`
Deps: JwtService

**class JwtService** [Service]
Path: `src/main/java/uy/plomo/cloud/security/JwtService.java`
Methods: `generateToken()`, `extractAllClaims()`

### uy.plomo.cloud.services

**class ArchiveService** [Service, ConditionalOnProperty]
Path: `src/main/java/uy/plomo/cloud/services/ArchiveService.java`
Deps: GatewayRepository, S3Client, TelemetryService
Methods: `archiveYesterday()`

**class AthenaService** [Service, ConditionalOnProperty]
Path: `src/main/java/uy/plomo/cloud/services/AthenaService.java`
Deps: AthenaAsyncClient
Methods: `query()`

**class GatewayEventBroadcaster** [Service]
Path: `src/main/java/uy/plomo/cloud/services/GatewayEventBroadcaster.java`
Methods: `subscribe()`, `broadcast()`

**class GatewayService** [Service]
Path: `src/main/java/uy/plomo/cloud/services/GatewayService.java`
Deps: GatewayRepository, PasswordEncoder, TunnelRepository, UserRepository
Methods: `changePassword()`, `registerUser()`, `getUserWithGateways()`, `getUserSummary()`, `getGatewaySummary()`, `getTunnelList()`, `getTunnelDetail()`, `registerGateway()`, `createTunnel()`, `updateTunnel()`

**class MqttService** [Service]
Path: `src/main/java/uy/plomo/cloud/services/MqttService.java`
Deps: GatewayEventBroadcaster, PendingRequestsService, TelemetryService
Methods: `init()`, `onAttemptingConnect()`, `onConnectionSuccess()`, `onConnectionFailure()`, `onDisconnection()`, `onStopped()`, `sendAsync()`

**class PendingRequestsService** [Service]
Path: `src/main/java/uy/plomo/cloud/services/PendingRequestsService.java`
Methods: `create()`, `complete()`, `cancel()`, `failAll()`, `cleanupExpired()`, `getPendingCount()`

**class PortPoolService** [Service]
Path: `src/main/java/uy/plomo/cloud/services/PortPoolService.java`
Deps: LightsailRemoteAccess, PortPool, TunnelRepository
Methods: `reconcilePortPool()`, `assignPort()`, `releasePort()`

**class TelemetryService** [Service]
Path: `src/main/java/uy/plomo/cloud/services/TelemetryService.java`
Deps: DynamoDbAsyncClient
Methods: `save()`, `query()`

### uy.plomo.cloud.utils

**class ByteArrayOutputFile** implements OutputFile
Path: `src/main/java/uy/plomo/cloud/utils/ByteArrayOutputFile.java`
Deps: ByteArrayOutputStream
Methods: `create()`, `createOrOverwrite()`, `toByteArray()`, `write()`, `write()`

**class TelemetryAggregator**
Path: `src/main/java/uy/plomo/cloud/utils/TelemetryAggregator.java`
Methods: `aggregate()`, `parseWindow()`, `parseFn()`

