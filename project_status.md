# AgentGate — Project Status & Session Memory

## 1. Tasks Completed (Steps 1–11)
- **Step 1 — Scaffolding & Architecture Boundaries:**
  - Configured Gradle 9.2.0 Wrapper ([gradlew](file:///e:/Assignment/gradlew), [build.gradle](file:///e:/Assignment/build.gradle), [settings.gradle](file:///e:/Assignment/settings.gradle)) for Spring Boot 4.0.0, Java 21 (Virtual Threads), Spring AI 2.0.0 MCP, Spring Security 7 OAuth2, Spring Modulith 2.0.0, ArchUnit 1.4.0, Resilience4j 2.4.0 (`resilience4j-spring-boot4`), Bucket4j + Redis + Caffeine, Flyway + PostgreSQL, JSON Schema Validator 3.0.0, JAXB API 2.3.1, and Testcontainers 2.x.
  - Configured [Dockerfile](file:///e:/Assignment/Dockerfile), [docker-compose.yml](file:///e:/Assignment/docker-compose.yml), [.env.example](file:///e:/Assignment/.env.example), [application.yml](file:///e:/Assignment/src/main/resources/application.yml), [logback-spring.xml](file:///e:/Assignment/src/main/resources/logback-spring.xml), and [AgentGateProperties.java](file:///e:/Assignment/src/main/java/com/cse444/agentgate/shared/config/AgentGateProperties.java).
  - Defined 8 Spring Modulith bounded contexts (`shared`, `registry`, `identity`, `policy`, `guard`, `approval`, `audit`, `gateway`) verified by [ArchitectureRulesTest.java](file:///e:/Assignment/src/test/java/com/cse444/agentgate/ArchitectureRulesTest.java).
- **Step 2 — Core Domain & Tool Registry (FR1, FR8, FR10):**
  - Flyway `V1__init_tool_registry.sql` + `V6__update_modulith_event_publication.sql`, `RegisteredTool`, `GlobalKillSwitchState`, OpenAPI 3.x importer, REST & MCP downstream adapters, Resilience4j circuit breaker + dynamic `TimeLimiter`, and `/mcp/tools` health-filtered discovery.
- **Step 3 — Identity, Security & Policies (FR2, FR3, FR9, QA4):**
  - Flyway `V2__init_identity_and_policies.sql`, OAuth2 Client Credentials JWT (`/oauth2/token`), RBAC `UserDetailsService` + `@PreAuthorize` method security + RFC 9457 `ProblemDetail` handlers, `PolicyRule` versioning, startup `@PostConstruct` + 10s `@Scheduled` hot-reload snapshot, risk-tiered 5-minute fail-safe availability (`QA4`), dry-run mode, `AgentGateAuthorizationManager`, and Policy Playground.
- **Step 4 — Pluggable Guards & Rate Limits (FR5, FR6, QA1):**
  - Flyway `V3__init_limits_and_quotas.sql`, shared `PiiSanitizer`, ordered `InputCallGuard` / `OutputResultGuard` chains (`JsonSchemaValidationGuard` on `SchemaRegistry` 3.0.0, `ArgumentSmugglingSecurityGuard`, `RateLimitAndBlastRadiusGuard` with unified agent write blast-radius cap, `PiiRedactionOutputGuard`, `PayloadTruncationOutputGuard`).
- **Step 5 — Human Approval State Machine (FR4, FR8):**
  - Flyway `V4__init_approvals.sql`, sealed `ApprovalLifecycleState`, async webhook notifier, timeout sweeper, Kill-Switch cancellation `@EventListener`, PII-sanitized execution & resolution audit events, REST controller, and Thymeleaf console (`/approvals/console`).
- **Step 6 — Tamper-Evident Audit Trail & Front-Door Gateway (FR7, QA3, C4):**
  - Flyway `V5__init_audit_trail.sql`, `HMAC-SHA256` hash-chain calculator, `@TransactionalEventListener(phase = AFTER_COMPLETION, fallbackExecution = true)` audit consumer, chain verification, CSV export, `GatewayOrchestrationService` with Micrometer metrics & OTel `traceId` MDC, and `McpToolCallbackBridge`.
- **Step 7 — Full Test Suite Verified (25/25 Tests Green — C5, C6):**
  - `.\gradlew.bat --no-daemon test` passed **25/25** against real Testcontainers **PostgreSQL 16 + Redis 7.4**:
    - `ArchitectureRulesTest`: 6/6 passed
    - `GuardAndStatePatternUnitTest`: 4/4 passed
    - `PolicyAndConditionUnitTest`: 2/2 passed
    - `WebSecurityAndProblemDetailsSliceTest`: 4/4 passed
    - `AgentGateQualityAttributesIntegrationTest`: 9/9 passed (30 malicious calls 100% blocked/held, 200/200 legitimate calls passed, MCP + REST adapters, CircuitBreaker hiding downed tool, OAuth2 HTTP token + gateway call, Policy versioning/dry-run/playground, Approval edit/approve/reject/timeout, write blast-radius cap, CSV export, `/actuator/health` UP, `/v3/api-docs` 200, QA3 tampering detection, QA4 fail-safe outage, QA5 OpenAPI import + kill switches).
- **Step 8 — Incremental Git History & Live Docker/k6 Benchmark Evidence (C9, C11):**
  - Initialized `git` on `main` and pushed incremental commits to `origin/main` with `.env`/key ignores.
  - Executed live Docker Compose benchmark (`postgres`, `redis`, `agentgate`, `mock-downstream`), diagnosed & resolved QA2 `59.88 s` HikariCP/audit/ZGC bottleneck, re-ran both `qa2_gateway_latency.k6.js` (**`5 ms` p95 overhead, `17.29 ms` p95 HTTP, `5,977/5,977` `100.00%` pass**) and `qa1_security_suite.k6.js` (**`30/30` malicious blocked, `200/200` legitimate pass**), verified `6,207` hash-chained audit entries (`intact: true`) + OpenAPI import (`qa5_get_order`) + `docker stats` (`712.3 MiB` post-load), and authored [EVIDENCE_REPORT.md](file:///e:/Assignment/docs/EVIDENCE_REPORT.md).

- **Step 9 — Review Fixes for FR4, FR1/FR10 and C2 (2026-11-18, AI-Usage Log Cases 11–14, branch `fix/review-fr4-mcp-c2`):**
  - FR4: agent polls its own result at `GET /api/v1/gateway/approvals/{approvalId}` (`statusUrl` in the held payload, `resultStatus` + `terminal`); a downstream failure after approval ends as `EXECUTION_FAILED` (`execution_status`, `V7`).
  - FR4 security: the approver webhook URL is admin-configured per tool (`approvalWebhookUrl`, `V8`) or `AGENTGATE_APPROVAL_WEBHOOK_URL`, never taken from the agent request; redacted arguments, timeout applied, sent after commit, plus `approval.resolved`.
  - FR1/FR10: `McpToolListSynchronizer` keeps the live MCP server tool list in step with the registry (5 s, and immediately on a kill switch).
  - C2: no secret defaults, `dev`/`docker`/`test` profiles, `.env` import, secret length validation, Compose `${VAR:?}`.
  - One commit per fix; every commit passes the full suite on JDK 21 + Testcontainers PostgreSQL 16 / Redis 7.4 (29 → 30 → 31 → 31 tests).
  - Live re-verification (2026-11-18): `docker compose up` with a real `.env` (docker profile; `V7`/`V8` applied on the existing volume); QA2 **`p(95) = 1 ms`** overhead / `5.97 ms` HTTP (`6,000/6,000`), QA1 **`30/30`** blocked + **`200/200`** legitimate, **`24,227`** audit entries intact, `735.2 MiB` stack RAM post-load. Two earlier QA2 runs failed under host memory pressure (only `0.8 GB` of `7.7 GB` free) and one QA1 run hit the FR6 daily quota — recorded with root causes in `EVIDENCE_REPORT.md` §7.
- **Step 10 — Real QA4 Fail-Safe, MCP Client, Permitted Discovery and CI Live Checks (2026-11-19, AI-Usage Log Cases 15–17):**
  - QA4: `FailSafeCaches` last-known-good caches for agents, tools and limits, non-transactional cached reads, a 2 s policy-store probe (`policyStore` health component), Hikari 2 s connection / 30 s socket timeouts, and an in-order audit retry queue. Verified with the PostgreSQL container paused (LOW call in 8 ms, MEDIUM/HIGH fail closed, outage call audited after recovery) in Testcontainers and live.
  - FR1: `McpDownstreamToolAdapter` uses the MCP Java SDK client (`initialize` handshake, Streamable HTTP or SSE); `GET /mcp/tools` (now in the gateway module) lists only the tools each agent's policies allow.
  - C5: `load-tests/run-live-qa.sh` (QA2, QA1, QA4 drill, QA3) runs locally and in the new `live-quality-attributes` CI job; QA1 no longer depends on run order.
  - Full suite 33/33; live script 4/4 checks passed (QA2 `p(95) = 2 ms` overhead, QA1 30/30 + 200/200, QA3 30,486 intact).
- **Step 11 — FR6 Alerts, Token-Bound End User and Spring Authorization Server (2026-11-19, AI-Usage Log Cases 18–20):**
  - FR6: `LimitBreachAlertService` raises one alert per agent, tool and breach type per 1-minute cooldown, stores it (`V9`), lists it at `GET /api/v1/limits/alerts` and posts it to the optional `AGENTGATE_LIMIT_ALERT_WEBHOOK_URL`.
  - FR2/QA1: the end user is the token's `delegated_user`; naming another user in the body or `X-On-Behalf-Of-User` is rejected with `403 Delegated User Mismatch` and audited. The k6 QA1 suite gets one token per customer.
  - FR2: Spring Authorization Server (`identity.security`) issues the HS256 agent tokens, with introspection, revocation and metadata that lists only `client_credentials`; the hand-written `/oauth2/token` is gone (ADR-005).
  - Full suite 36/36; live script 4/4 (QA2 `p(95) = 2 ms` overhead, QA1 30/30 + 200/200, QA4 LOW call in 13 ms, QA3 36,735 intact); token, user-binding and FR6 alert drills in `EVIDENCE_REPORT.md` §9.
- **Step 12 — FR3 Fail-Closed Policy Conditions (2026-11-19, AI-Usage Log Case 21):**
  - A policy rule whose stored conditions cannot be read (or lack a field, operator or expected value) now denies every call that reaches it instead of acting as an unconditional rule; a dry-run DENY rule still never blocks. Covered by `PolicyAndConditionUnitTest#unreadableStoredConditionsFailClosed`; full suite 37/37.

## 2. Current State of the Application
- Full test suite (unit, slice, ArchUnit/Modulith, and Testcontainers PostgreSQL + Redis integration tests) is **37/37 passing** (`0` failures, `0` skipped) as of 2026-11-19.
- Live checks (`bash load-tests/run-live-qa.sh`, also run in CI) pass **QA1 (`100%`)**, **QA2 (`p(95) = 2 ms` overhead @ 100 RPS)**, **QA3 (`36,735/36,735` intact)**, **QA4 (real PostgreSQL outage drill)**; **QA5** (live OpenAPI import) and **C1** (`735.2 MiB` total stack RAM < `8 GB`) as of 2026-11-18.
- Benchmark tip: close memory-heavy apps before QA2 (keep `>= 1.5 GB` host RAM free); see EVIDENCE_REPORT §7.
- All evidence, ADRs, AI-Usage Log entries (Cases 1–21), and fixes are documented.

## 3. Immediate Next Steps for Next Session (TODOs)
1. **Mid-Project Checkpoint Change Request (Requirement C10):** When the instructor issues the checkpoint change request, implement it as a new pluggable bean/module with zero edits to core orchestrators, and log touched files.
2. **Live Defence Preparation (Requirement C12):** Rehearse with [DEFENCE_SCRIPT.md](file:///e:/Assignment/docs/DEFENCE_SCRIPT.md): the ADR-003 walkthrough with the live QA4 drill, cheat cards for the other ADRs, the verified on-the-spot change drills (new input guard, new output redaction, new policy operator) and the Q&A bank.
