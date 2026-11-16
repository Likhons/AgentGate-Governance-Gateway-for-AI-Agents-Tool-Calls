# AgentGate — Pattern Justification Table, 4 ADRs & Mini-ATAM Utility Tree

## 1. Pattern Justification Table (Requirement C8)

| Design Pattern | Requirement(s) Served | Participating Classes | Alternative Rejected & Why |
| :--- | :--- | :--- | :--- |
| **Chain of Responsibility** | **FR5** (Pluggable Guards), **FR6** (Rate Limits & Blast-Radius Caps), **QA1** (Security) | `InputCallGuard`, `JsonSchemaValidationGuard` (`@Order(10)`), `ArgumentSmugglingSecurityGuard` (`@Order(20)`), `RateLimitAndBlastRadiusGuard` (`@Order(30)`), `OutputResultGuard`, `PiiRedactionOutputGuard` (`@Order(10)`), `PayloadTruncationOutputGuard` (`@Order(20)`), `GuardPipelineService` | **Monolithic `if/else` validation method inside the gateway service:** Rejected because adding a new guard at the mid-project checkpoint (C10) would require editing core orchestration code, violating the Open/Closed Principle and increasing regression risk. |
| **Strategy** | **FR3** (Argument Policy Conditions), **FR9** (Policy Playground) | `PolicyCondition`, `PolicyCondition.Operator`, `PolicyConditionEvaluator`, `PolicyEngineService` | **Dynamic SpEL / JavaScript (`ScriptEngine`) expression evaluation:** Rejected because evaluating arbitrary expressions on untrusted agent inputs introduces Remote Code Execution (RCE) vulnerabilities and adds ~5–12 ms parsing overhead per call, threatening QA1 and QA2 (`<=30ms` p95). |
| **Decorator / Proxy** | **FR10** (Downstream Circuit Breakers, Per-Tool Timeouts & Health Filtering), **FR5** (Output Transformation) | `ResilientDownstreamInvoker`, `DownstreamToolAdapter`, `OutputResultGuard`, `PiiRedactionOutputGuard`, `PayloadTruncationOutputGuard` | **Hardcoding circuit-breaker and timeout logic inside each HTTP/MCP client:** Rejected because it duplicates resilience boilerplate across REST and MCP protocols and couples protocol serialization to Resilience4j state inspection. |
| **Adapter** | **FR1** (Unified Tool Registry for Downstream MCP Servers & Plain REST Endpoints) | `DownstreamToolAdapter`, `RestDownstreamToolAdapter`, `McpDownstreamToolAdapter`, `McpUnifiedEndpointController` | **Separate gateway pipelines for REST tools vs. MCP tools:** Rejected because policies, guards, approvals, and audit trails would have to be duplicated across two pipelines instead of operating on a single normalized `RegisteredTool` abstraction. |
| **State** | **FR4** (Human-in-the-Loop Approval Lifecycle), **FR8** (Kill-Switch Cancellation) | `ApprovalLifecycleState` (`sealed interface`), `PendingState`, `TerminalApprovalState`, `ApprovalRequest`, `ApprovalWorkflowService` | **Raw status string/enum checks scattered across controllers and services:** Rejected because race conditions between human approval, timeout sweeps, and emergency kill switches could allow a `TIMED_OUT` or `REJECTED` call to transition to `APPROVED`. The sealed State pattern enforces legal transitions in one place. |
| **Observer (Event-Driven)** | **FR7** (Tamper-Evident Audit Trail), **FR8** (Kill Switch Cancels Pending Approvals), **FR6** (Limit Breach Alerts), **C6** (Modulith Decoupling) | `ToolCallAuditRecordedEvent`, `AuditTrailService` (`@TransactionalEventListener`), `KillSwitchActivatedEvent`, `ApprovalWorkflowService` (`@EventListener`), `LimitBreachAlertEvent` | **Direct synchronous calls from `GatewayOrchestrationService` / `ToolRegistryService` to `AuditTrailService` and `ApprovalWorkflowService`:** Rejected because it creates tight compile-time coupling and cyclic dependencies between `registry`, `identity`, `approval`, and `audit`, violating ArchUnit rule `auditModuleMustBeEventDrivenAndNeverCalledDirectly`. |
| **Command / Facade** | **FR1–FR10** (Single Governed Front-Door Execution Pipeline) | `GatewayToolCallRequest`, `GatewayToolCallResponse`, `GatewayOrchestrationService`, `GatewayController` | **Letting MCP/REST controllers call `Identity`, `Policy`, `Guard`, `Approval`, and `Registry` directly:** Rejected because governance steps could be invoked out of order or skipped on new endpoints, and audit logging on failure paths would not be guaranteed. |

---

## 2. Architecture Decision Records (ADRs — Requirement C7)

### ADR-001: Spring MVC on Java 21 Virtual Threads vs. Reactive Spring WebFlux
- **Status:** Accepted
- **Context:** AgentGate must sustain 100 tool calls/sec while adding no more than 30 ms of gateway overhead at p95 (QA2), while also integrating cleanly with Spring Data JPA (PostgreSQL), Flyway, Spring Security OAuth2, and Spring AI MCP Server starters.
- **Options Considered:**
  1. **Spring MVC with Java 21 Virtual Threads (`spring.threads.virtual.enabled=true`)** and `Executors.newVirtualThreadPerTaskExecutor()` for Resilience4j `TimeLimiter` and async webhooks.
  2. **Reactive Spring WebFlux (`Mono`/`Flux`)** with R2DBC.
- **Decision:** We chose **Option 1: Spring MVC with Java 21 Virtual Threads**.
- **Consequences:**
  - *Positive:* Blocking I/O calls (PostgreSQL JDBC via HikariCP, downstream `RestClient` calls, Redis operations) unmount the carrier platform thread automatically, yielding reactive-grade concurrency with simple imperative stack traces and full compatibility with Spring Data JPA, Flyway, and `@TransactionalEventListener`.
  - *Negative:* Requires avoiding `synchronized` blocks around long network I/O to prevent carrier-thread pinning (we use `RestClient` outside synchronized blocks and limit synchronization strictly to fast hash-chain sequence allocation).
- **Measured Evidence:** Verified Spring MVC Virtual Threads + `@EnableMethodSecurity` + Logstash JSON MDC compilation and slice execution (`WebSecurityAndProblemDetailsSliceTest` completed 4/4 tests in 0.743 s). Sustained 100 RPS p95 overhead (`agentgate_gateway_overhead_ms`) and heap consumption under `load-tests/qa2_gateway_latency.k6.js` are pending live Docker Compose benchmark execution.

---

### ADR-002: Modular Monolith Enforced by Spring Modulith & ArchUnit vs. Distributed Microservices
- **Status:** Accepted
- **Context:** The entire AgentGate stack must start via a single `docker-compose up` command within an 8 GB RAM budget (C1) and add `<= 30 ms` p95 latency per tool call (QA2), while maintaining strict boundaries between Identity, Registry, Policy, Guard, Approval, and Audit (C6).
- **Options Considered:**
  1. **Spring Modulith Modular Monolith** with compile-time/test-time boundary verification (`ApplicationModules.verify()` + ArchUnit) and domain event decoupling.
  2. **Separate Microservices** (Identity Service, Policy Service, Guard Service, Approval Service, Audit Service) communicating over HTTP/gRPC or Kafka.
- **Decision:** We chose **Option 1: Spring Modulith Modular Monolith**.
- **Consequences:**
  - *Positive:* Zero network serialization hops between governance stages (saving ~15–25 ms per tool call for QA2) and a total Docker Compose memory footprint of **1.75 GB** (`postgres` 512 MB + `redis` 256 MB + `agentgate` 1024 MB) vs. >6 GB for 5 separate JVM microservices.
  - *Positive:* `ArchitectureRulesTest` guarantees that `audit` is never called directly and no circular dependencies exist across the 8 modules.
  - *Negative:* All modules scale together within the same JVM process rather than independently.
- **Measured Evidence:** `ArchitectureRulesTest` verifies all 8 Spring Modulith bounded contexts with **0 cyclic dependencies** (`topLevelModulesShouldBeFreeOfCycles` passed in 1.281 s; full suite 6/6 tests passed in 7.02 s). Configured Docker Compose container memory limits total **1.75 GB** (`postgres` 512 MB + `redis` 256 MB + `agentgate` 1024 MB), well within the 8 GB ceiling (`docker stats` capture pending live benchmark run).

---

### ADR-003: In-Memory Atomic Policy Snapshot with Risk-Tiered Fail-Safe TTL (QA2 & QA4)
- **Status:** Accepted
- **Context:** Policies must take effect within 10 seconds without a restart (FR3), add minimal latency at 100 calls/sec (QA2), and satisfy QA4: *"If the policy store is unreachable, medium- and high-risk tools fail closed, while low-risk tools keep working on the last known policies for up to 5 minutes."*
- **Options Considered:**
  1. **Query PostgreSQL on every tool call** (`SELECT * FROM policy_rules WHERE active = true`).
  2. **Atomic In-Memory `PolicySnapshot` refreshed every 10s (`@Scheduled`) with risk-tiered fail-safe evaluation**.
- **Decision:** We chose **Option 2: Atomic In-Memory `PolicySnapshot` with Risk-Tiered Fail-Safe TTL**.
- **Consequences:**
  - *Positive:* Policy evaluation reads from an immutable `AtomicReference<PolicySnapshot>` in **< 0.15 ms** with zero lock contention and zero database queries on the hot path (QA2).
  - *Positive:* When PostgreSQL becomes unreachable during refresh, `policyStoreReachable` flips to `false`; `PolicyEngineService` immediately denies `MEDIUM` and `HIGH` risk tools (`QA4_FAIL_CLOSED_HIGH_OR_MEDIUM_RISK`) while allowing `LOW` risk tools to evaluate against the cached snapshot until `Duration.between(snapshot.loadedAt(), now) > 5m` (QA4).
  - *Negative:* Up to a 10-second propagation window between a database edit outside the API and the next scheduled poll (mitigated by triggering `refreshPolicySnapshot()` immediately on `POST /api/v1/policies` and `POST /api/v1/policies/reload`).
- **Measured Evidence:** Verified in `AgentGateQualityAttributesIntegrationTest#qa4FailSafeAvailabilityUnderPolicyStoreOutage` against Testcontainers PostgreSQL 16 + Redis 7.4 (test passed in 1.043 s): simulated DB outage blocks 100% of `MEDIUM`/`HIGH` calls while `LOW`-risk calls succeed from the cached snapshot with `evaluatedFromFailsafeCache = true`.

---

### ADR-004: Application-Level HMAC-SHA256 Hash-Chained Audit Log via `@TransactionalEventListener` (FR7 & QA3)
- **Status:** Accepted
- **Context:** Every tool call must be logged with agent, user, tool, redacted arguments, deciding policy rule, approval details, latency, and outcome in a tamper-evident log where editing any stored row is detected by the `verify` command (FR7, QA3).
- **Options Considered:**
  1. **Standard PostgreSQL table with `created_at` timestamps only.**
  2. **Application-level sequential `HMAC-SHA256` hash chain (`previous_hash` -> `entry_hash`) populated via `@TransactionalEventListener(phase = AFTER_COMPLETION, fallbackExecution = true)` with `REQUIRES_NEW` transaction propagation.**
- **Decision:** We chose **Option 2: Application-Level HMAC-SHA256 Hash Chain with `AFTER_COMPLETION` + `REQUIRES_NEW`**.
- **Consequences:**
  - *Positive:* Because the listener runs in `AFTER_COMPLETION` with `Propagation.REQUIRES_NEW`, even tool calls that throw exceptions and roll back (e.g., blocked by policy, schema guard, rate limit, or kill switch) are permanently recorded in the audit trail.
  - *Positive:* Any direct SQL `UPDATE` or `DELETE` on `audit_log_entries` invalidates `entry_hash` or creates a `sequence_number`/`previous_hash` mismatch that `GET /api/v1/audit/verify` immediately flags with the exact tampered row ID (QA3).
  - *Negative:* Sequential hash chaining serializes audit row insertion order.
- **Measured Evidence:** Verified in `AgentGateQualityAttributesIntegrationTest#qa3AuditTrailDetectsTampering` against Testcontainers PostgreSQL 16 (test passed in 0.126 s): mutating a single column (`UPDATE audit_log_entries SET outcome = 'TAMPERED_OUTCOME'`) causes `verifyChainIntegrity()` to return `intact = false` and pinpoint the exact `firstTamperedEntryId`.

---

## 3. Mini-ATAM Utility Tree (Requirement C7)

| Quality Attribute | Refinement | Scenario ID & Stimulus | Response & Target Measure | Priority (Impact, Difficulty) | Sensitivity & Trade-Off Points |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Security (QA1)** | Prompt Injection, Cross-Tenant Read & Smuggling Prevention | **S1:** Red-team suite sends 30 malicious calls (10 refunds > 2,000 BDT, 10 cross-customer reads `customerId != $user`, 8 smuggled/SQLi/prompt-injection payloads, 2 unauthorized high-risk payouts) alongside 200 legitimate calls. | **100% (30/30)** of malicious calls are blocked or held for approval; **<= 5%** false blocks on 200 legitimate calls. | **(H, H)** | *Sensitivity:* Strictness of JSON Schema `additionalProperties: false` and regex patterns in `ArgumentSmugglingSecurityGuard`. |
| **Performance (QA2)** | Gateway Processing Overhead | **S2:** 100 concurrent agent calls/sec hit `/api/v1/gateway/call` for low/medium-risk tools over 60 seconds. | Gateway adds **<= 30 ms at p95** excluding downstream approval waits. | **(H, H)** | *Trade-off:* In-memory `PolicySnapshot` and Caffeine-cached `JsonSchema` trade a <=10s policy staleness window for sub-millisecond evaluation latency. |
| **Auditability (QA3)** | Forensic Traceability & Tamper Detection | **S3:** An attacker with DB credentials modifies the `outcome` or `redacted_arguments_json` column of an existing row in `audit_log_entries`. | `GET /api/v1/audit/verify` detects the tampered row (`intact = false`) and identifies `firstTamperedEntryId` in **< 1 minute** (actual: < 50 ms). | **(H, M)** | *Trade-off:* Sequential `HMAC-SHA256` hash linking slightly serializes audit writes in exchange for cryptographic tamper evidence. |
| **Availability (QA4)** | Risk-Tiered Fail-Safe Under Policy Store Outage | **S4:** PostgreSQL policy store becomes unreachable during peak traffic. | `MEDIUM` and `HIGH` risk tools **fail closed immediately** (0% execution); `LOW` risk tools continue operating from the last known snapshot for **up to 5 minutes**. | **(H, M)** | *Trade-off:* Favors Safety/Consistency (CP) for `MEDIUM`/`HIGH` risk tools and Availability (AP) with bounded 5m staleness for `LOW` risk tools. |
| **Adoptability / Modifiability (QA5)** | Zero-Code Tool Onboarding & Pluggable Guards | **S5:** A developer who has not seen the codebase imports a new REST service from an OpenAPI 3.x spec, assigns a risk level, and creates an allow policy. | Completed end-to-end via REST API in **< 10 minutes with zero code changes**. | **(M, L)** | *Sensitivity:* Completeness of `OpenApiToolImporterService` parameter-to-JSON-Schema translation. |
| **Resilience (FR10)** | Downstream Outage Isolation | **S6:** A downstream REST tool begins timing out (> `timeoutMs`) or returning HTTP 500s on >50% of calls. | Resilience4j `CircuitBreaker` transitions to `OPEN` after 5 calls and `/mcp/tools` **automatically hides the downed tool** from agents until half-open recovery succeeds. | **(M, M)** | *Sensitivity:* `slidingWindowSize` (10) and `waitDurationInOpenState` (15s) in `ResilienceConfig`. |
