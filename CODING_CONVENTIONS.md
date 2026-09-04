# Coding Conventions

Read this before writing new code. These are patterns extracted from the actual codebase — follow them for consistency.

## Async
- Controllers return `CompletableFuture<ResponseEntity<T>>` using chains: `supplyAsync → thenCompose → thenApply`
- Use `thenCompose` to chain async calls, `thenApply` for sync transforms on the result
- Use `handle()` when you need to recover from errors AND return a value; `exceptionally()` when you only need to handle the error path
- Timeouts via `orTimeout(N, TimeUnit.SECONDS)` on the future — no Thread.sleep, no blocking `.get()`
- No `@Async` annotation anywhere — all async is explicit CompletableFuture

## Error handling
- Throw `ResponseStatusException(HttpStatus.X, "message")` for HTTP errors at the controller/service boundary
- Internal domain errors use static inner exception classes inside the service (e.g. `GatewayService.ResourceNotFoundException`) — GlobalExceptionHandler translates them to HTTP responses
- In `exceptionally()` blocks, unwrap cause: `Throwable cause = ex.getCause() != null ? ex.getCause() : ex`
- Pattern matching for instanceof: `if (cause instanceof GatewayService.ResourceNotFoundException r)`

## Collections
- Streams for transformations and filtering: `.stream().map(...).filter(...).collect(...)` or `.toList()`
- For-each is fine for simple iterations without a transform result
- No manual index loops

## DTOs
- Records only — no mutable DTO classes
- Access via generated accessors: `req.gatewayId()`, `req.srcPort()`, not getters

## Injection & configuration
- Constructor injection always — no `@Autowired` field injection
- `@Value("${property.name}")` as constructor parameter or field for config values
- `@Transactional` on service methods that write to DB, not on controllers
- `@ConditionalOnProperty` to gate optional services (ArchiveService, AthenaService) — do not instantiate AWS clients unconditionally

## Logging
- `@Slf4j` (Lombok) on every class that logs — no manual `Logger` fields
- Use `log.info` for normal flow, `log.warn` for recoverable issues, `log.error` for failures
- Include relevant IDs in log messages: `log.info("Starting tunnel {} on gateway {}", tunnelId, gwId)`

## Controllers
- Thin: delegate business logic to `@Service` classes
- Handle CompletableFuture composition and `ResponseEntity` wrapping in the controller
- No business logic, no DB access, no direct AWS calls in controllers

## Initialization
- `@EventListener(ApplicationReadyEvent.class)` for post-startup logic (e.g. reconcilePortPool)
- `@PostConstruct` for bean-level init that needs injected fields (e.g. MqttService.init)

## HTTP responses
- `200 OK` (`ResponseEntity.ok(body)`): normal successful read or action with body
- `201 CREATED`: POST that creates a resource. Two forms used:
  - `ResponseEntity.status(HttpStatus.CREATED).body(Map.of(...))` when no Location URI applies
  - `ResponseEntity.created(URI.create("/api/v1/...")).body(...)` when there's a canonical resource URL (preferred for nested resources)
- `204 NO CONTENT` (`ResponseEntity.noContent().build()`): PUT/DELETE with no body
- Standard error mapping via `ResponseStatusException` thrown from services:
  - `UNAUTHORIZED` (401) — invalid credentials
  - `NOT_FOUND` (404) — domain `ResourceNotFoundException` (handled by GlobalExceptionHandler)
  - `CONFLICT` (409) — domain `ConflictException`
  - `BAD_GATEWAY` (502) — invalid response from gateway over MQTT
  - `SERVICE_UNAVAILABLE` (503) — gateway request failed
  - `GATEWAY_TIMEOUT` (504) — gateway did not respond within timeout
- Error body is always `ErrorResponse(error, message)` — both fields populated, no nulls
- Never return entity classes directly from controllers — wrap in `Map<String, Object>` or a record DTO

## JPA / Entities
- Default `protected NoArg()` constructor required by JPA; never call it from app code
- Static factory `create(...)` for construction — never `new Entity()` from services
- Public getters only; setters are restricted to fields that legitimately mutate (e.g. `setStatus`, `setState`, `setPasswordHash`)
- All relations are `FetchType.LAZY` — never `EAGER`
- Enums always `@Enumerated(EnumType.STRING)` — never ordinal (forward-compatible schema)
- Owned aggregates use `cascade = CascadeType.ALL, orphanRemoval = true` (see `Gateway.tunnels`)
- `@ManyToOne` always paired with `@JoinColumn(name = "...", nullable = false)` — owner is always required
- Column naming: explicit `@Column(name = "snake_case")` whenever the Java field name doesn't match the SQL column
- Large strings (e.g. public keys) use `columnDefinition = "TEXT"` — don't rely on default VARCHAR
- ID strategy:
  - Synthetic numeric ID: `@GeneratedValue(strategy = GenerationType.IDENTITY)` (User)
  - Application-supplied String key: plain `@Id` on String field (Gateway)
  - UUID String: generated in the static factory (`UUID.randomUUID().toString()`), `@Column(length = 36)` (Tunnel)
- Schema changes go through Flyway (`V{N}__description.sql`) — never let Hibernate auto-update

## Anti-patterns (don't do these)
- No `Thread.sleep`, no blocking `.get()` on CompletableFuture — use `orTimeout()` + async chains
- No `@Autowired` field injection — constructor injection only (and no `@Autowired` on constructors either, it's implicit since Spring 4.3)
- No `@Async` annotation — async is explicit via `CompletableFuture.supplyAsync(...)`
- No getters/setters on records — use record accessors (`req.name()`, not `req.getName()`)
- No manual `Logger LOG = LoggerFactory.getLogger(...)` fields — `@Slf4j` always
- No checked exceptions on service signatures — wrap and rethrow as `ResponseStatusException` or domain exception
- No HTTP types (`ResponseEntity`, `HttpStatus`) inside `@Service` classes — services throw, controllers translate
- No JPA types (`EntityManager`, repositories) inside controllers — go through a service
- No returning JPA entities from controllers — map to DTO/Map
- No `try { ... } catch (Exception e) { log.error(...); throw e; }` — let it propagate to `GlobalExceptionHandler`
- No `EnumType.ORDINAL` — always `STRING`
- No new dependencies without updating `build.gradle` first
- No raw SQL outside Flyway migrations or `@Query` annotations on repository methods
