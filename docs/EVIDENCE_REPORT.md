# AgentGate — Verification & Benchmark Evidence Report (Requirement C9)

> **Environment:** Docker Compose (`postgres:16-alpine`, `redis:7.4-alpine`, `eclipse-temurin:21-jre-alpine`, `nginx:1.27-alpine` under `--profile benchmark`) on Windows Docker Desktop (`8 GB` host laptop budget ceiling per `C1`), load-tested with `grafana/k6`.

---

## 1. Executive Summary Matrix (C1, C5, C6, QA1–QA5)

| Requirement / Attribute | Verification Method | Target SLA | Measured Result | Status |
| :--- | :--- | :--- | :--- | :--- |
| **C5 & C6: Automated Unit, Slice, ArchUnit & Integration Suite** | `./gradlew --no-daemon test` (Testcontainers PostgreSQL 16 + Redis 7.4) + CI job `live-quality-attributes` running `load-tests/run-live-qa.sh` | 100% tests passing, 0 cyclic Modulith deps, every QA measure scripted in CI | **36 / 36 tests passed** on 2026-11-19 (`0` failed, `0` skipped; `AgentGateQualityAttributesIntegrationTest` `18/18` in `14.623s`, `ArchitectureRulesTest` `6/6` in `12.503s`); live QA1–QA4 script **4 / 4 checks passed** ([§9](#9-spring-authorization-server-token-bound-end-users-and-fr6-alerts-2026-11-19)) | PASS |
| **QA1: Security & Injection Defense** | Live Docker `grafana/k6` (`load-tests/qa1_security_suite.k6.js`, via `run-live-qa.sh`) + JUnit IT | `100%` malicious blocked; `>= 95%` legitimate pass | **`100.00%` (30 / 30)** malicious blocked/held; **`100.00%` (200 / 200)** legitimate passed, each call with its own customer's token; naming another end user is rejected with `403` (2026-11-19, [§9](#9-spring-authorization-server-token-bound-end-users-and-fr6-alerts-2026-11-19)) | PASS |
| **QA2: Gateway Latency @ 100 RPS** | Live Docker `grafana/k6` (`load-tests/qa2_gateway_latency.k6.js`, `60s`, via `run-live-qa.sh`) | Overhead `p(95) < 30ms`; End-to-End `p(95) < 40ms`; `>= 95%` pass | Gateway overhead **`p(95) = 2 ms`** (`avg = 1.13 ms`); HTTP `p(95) = 10.62 ms` (`avg = 5.96 ms`); **`100.00%` (6,001 / 6,001)** pass on 2026-11-19 with Spring Authorization Server tokens ([§9](#9-spring-authorization-server-token-bound-end-users-and-fr6-alerts-2026-11-19)); earlier runs and two failed runs in §3 and §7 | PASS |
| **QA3: Hash-Chained Audit Trail & Tamper Detection** | Live `GET /api/v1/audit/verify` + Testcontainers `qa3AuditTrailDetectsTampering` | Tamper detected `< 1 min`; zero broken chain links | **`36,735 / 36,735`** live entries verified **`intact: true`** (2026-11-19, after five outage drills); SQL row edit pinpoints `firstTamperedEntryId` | PASS |
| **QA4: Fail-Safe Under Policy Outage** | Testcontainers `qa4RealDatabaseOutageKeepsLowRiskToolsWorkingAndFailsClosed` (PostgreSQL container paused) + live `docker pause` drill in `run-live-qa.sh` + flag-based `qa4FailSafeAvailabilityUnderPolicyStoreOutage` | `MEDIUM`/`HIGH` fail closed; `LOW` works `<= 5m` on the last known state | With PostgreSQL **paused**: `LOW` call **`200 EXECUTED` in 8–13 ms**, `MEDIUM`/`HIGH` **`409` fail closed**, `policyStore` health `DOWN` → `UP`, outage-time call hash-chained after recovery ([§8](#8-real-outage-qa4-live-quality-script-and-ci-2026-11-19), [§9](#9-spring-authorization-server-token-bound-end-users-and-fr6-alerts-2026-11-19)) | PASS |
| **QA5: Zero-Code OpenAPI Onboarding** | Live `POST /api/v1/tools/import-openapi` + `GET /api/v1/tools` | Onboard new REST API with `0` code changes | `qa5_get_order` imported from OpenAPI `3.0.1` JSON, visible in registry (`circuitBreakerState: CLOSED`) | PASS |
| **C1: Resource Budget (`< 8 GB RAM`)** | `docker stats --no-stream` across all 4 running containers | Total `< 8,192 MiB` | **`528.2 MiB` idle**, **`735.2 MiB` post-load** on 2026-11-18 (`< 9%` of 8 GB ceiling); `656.5 MiB` after the 2026-11-19 live run | PASS |

---

## 2. Automated Gradle Test Suite Results (Latest Run: 2026-11-19, after AI-Usage Log Cases 18–20)

Executed with JDK 21 (`--no-daemon test`) against Testcontainers **PostgreSQL 16** and **Redis 7.4**:

| Test Suite | Tests | Failures | Errors | Skipped | Duration |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `com.cse444.agentgate.ArchitectureRulesTest` | 6 | 0 | 0 | 0 | 12.503 s |
| `com.cse444.agentgate.AgentGateQualityAttributesIntegrationTest` | 18 | 0 | 0 | 0 | 14.623 s |
| `com.cse444.agentgate.WebSecurityAndProblemDetailsSliceTest` | 6 | 0 | 0 | 0 | 1.409 s |
| `com.cse444.agentgate.GuardAndStatePatternUnitTest` | 4 | 0 | 0 | 0 | 0.011 s |
| `com.cse444.agentgate.PolicyAndConditionUnitTest` | 2 | 0 | 0 | 0 | 0.004 s |
| **Total** | **36** | **0** | **0** | **0** | **28.550 s** |

*Earlier runs:* 33 / 33 after Cases 15–17 (2026-11-19); 31 / 31 on 2026-11-18 after Cases 11–14 (each of those four commits passed on its own: 29 → 30 → 31 → 31);
25 / 25 after the QA2 optimization (integration suite 9 tests).

Covered integration test scenarios in [AgentGateQualityAttributesIntegrationTest.java](../src/test/java/com/cse444/agentgate/AgentGateQualityAttributesIntegrationTest.java):
- `qa1RedTeamSuiteIs100PercentBlockedOrHeldForApproval`
- `qa1Legitimate200CallsPassAndPiiIsRedacted`
- `fr1McpDownstreamFr10CircuitBreakerAndObservabilityEndpoints`
- `fr2OAuthHttpCallFr3VersioningDryRunAndFr9Playground`
- `fr4ApprovalLifecycleAndFr5Truncation`
- `fr6RateLimitAndWriteBlastRadiusCap`
- `qa3AuditTrailDetectsTampering`
- `qa4FailSafeAvailabilityUnderPolicyStoreOutage`
- `qa5OpenApiImportAndFr8KillSwitchCancelsPendingApprovals`
- `fr4AgentPollsFinalApprovalResult` *(Case 11: agent-scoped result endpoint with an explicit terminal status)*
- `fr4ApprovedCallWithFailingDownstreamReportsExecutionFailed` *(Case 11: `EXECUTION_FAILED` instead of a stuck `PENDING`)*
- `fr4ApproverWebhooksUseToolConfiguredUrlWithRedactedArguments` *(Case 12: admin-configured, PII-redacted approver webhooks)*
- `fr1McpServerToolListFollowsRegistryAtRuntime` *(Case 13: live MCP tool list follows the registry and kill switches)*
- `qa4RealDatabaseOutageKeepsLowRiskToolsWorkingAndFailsClosed` *(Case 15: PostgreSQL container paused; LOW works, MEDIUM/HIGH fail closed, audits catch up)*
- `fr1McpToolsListsOnlyToolsTheAgentIsPermittedToCall` *(Case 16: `/mcp/tools` shows each agent only its permitted tools)*
- `fr1McpDownstreamFr10CircuitBreakerAndObservabilityEndpoints` now asserts the real MCP handshake (`initialize` → `notifications/initialized` → `tools/call`) *(Case 16)*
- `fr6LimitBreachRaisesAlertOnceAndListsIt` *(Case 18: one alert per cooldown, ops webhook, `GET /api/v1/limits/alerts`)*
- `fr2DelegatedUserIsBoundToTheAgentToken` *(Case 19: body/header user spoofing rejected with `403` and audited)*
- `fr2SpringAuthorizationServerIssuesAgentTokens` *(Case 20: Spring Authorization Server metadata lists only `client_credentials`, token claims, introspection, disabled agent refused)*

---

## 3. QA2 Benchmark Iteration: Failed Run #1 vs. Optimized Run #2

### 3.1 Root Causes Exposed by Failed Run #1 (`100 RPS`, `60s`)
When [qa2_gateway_latency.k6.js](../load-tests/qa2_gateway_latency.k6.js) first executed against the unoptimized Docker stack, **all 3 thresholds failed** (`39.02%` pass rate, `5,814` dropped iterations, `59.88 s` `p95` overhead). Inspecting container logs and `docker stats` isolated four bottlenecks:

1. **HikariCP Pool Deadlock Across Outer `@Transactional` + `AFTER_COMPLETION` Listener:** `GatewayOrchestrationService#processToolCall` held `@Transactional` across Redis rate-limit evaluation and downstream REST I/O, exhausting all 20 connections while `AuditTrailService` (`AFTER_COMPLETION` + `REQUIRES_NEW`) blocked waiting for a second connection (`HikariPool-1 - Connection is not available, request timed out after 30027ms (total=20, active=20, idle=0, waiting=10)`).
2. **Audit Sequence Collision & Virtual-Thread Pinning:** `AuditTrailService#recordAuditEvent` declared `synchronized` inside Spring's `@Transactional(REQUIRES_NEW)` AOP proxy, releasing the monitor lock before JDBC commit completed (`PSQLException: ERROR: duplicate key value violates unique constraint "audit_log_entries_sequence_number_key"`, `Key (sequence_number)=(269) already exists`).
3. **5 Redundant Database Reads per Gateway Request:** Hot-path calls re-queried PostgreSQL for agent state (`isAgentEnabled`, `verifyAndResolveAgentIdentity`), tool metadata (`getRequiredToolByName`), kill switch state (`global_kill_switch_state`), and rate-limit overrides (`findByAgentClientIdAndToolName`).
4. **Excessive JVM Memory Retention with ZGC:** `-XX:+UseZGC -XX:MaxRAMPercentage=75.0` kept `agentgate-app` at **`1016 MiB / 1 GiB (99.26%)`** even while idle.

### 3.2 Engineering Fixes Applied
- Removed outer `@Transactional` from [GatewayOrchestrationService.java](../src/main/java/com/cse444/agentgate/gateway/service/GatewayOrchestrationService.java) so JDBC connections are never held during Redis/HTTP calls.
- Added 10-second Caffeine read-through caches with explicit invalidation on administrative mutations in [AgentIdentityService.java](../src/main/java/com/cse444/agentgate/identity/service/AgentIdentityService.java), [ToolRegistryService.java](../src/main/java/com/cse444/agentgate/registry/service/ToolRegistryService.java), and [RateLimitAndBlastRadiusGuard.java](../src/main/java/com/cse444/agentgate/guard/chain/RateLimitAndBlastRadiusGuard.java), plus lock-free `AtomicBoolean` reads in `isAllWriteToolsKilled()`.
- Refactored [AuditTrailService.java](../src/main/java/com/cse444/agentgate/audit/service/AuditTrailService.java) to enqueue hash-chain events into a non-blocking `ConcurrentLinkedQueue` drained by a single dedicated `agentgate-audit-writer` thread under a `ReentrantLock` + `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` in batches of up to `128` rows via `saveAllAndFlush`, calling `flushPendingAudits()` before audit searches, hash verification, CSV exports, and shutdown.
- Switched [Dockerfile](../Dockerfile) JVM entrypoint flags from ZGC to `-XX:+UseG1GC -Xms128m -Xmx512m -XX:MaxGCPauseMillis=10`.

### 3.3 Before vs. After Benchmark Comparison (`100 RPS`, `60s`)

| Metric | Failed Run #1 (Unoptimized) | Optimized Run #2 (G1GC + Batch Audit + Caffeine) | Target Threshold |
| :--- | :---: | :---: | :---: |
| **`agentgate_gateway_overhead_ms` p(95)** | `59.88 s` (`59,880 ms`) | **`5 ms`** | `< 30 ms` |
| **`agentgate_gateway_overhead_ms` med / avg** | `29.97 s` / `25.76 s` | **`1 ms` / `3.01 ms`** | — |
| **`http_req_duration` p(95)** | `59.88 s` (`59,880 ms`) | **`17.29 ms`** | `< 40 ms` |
| **`http_req_duration` med / avg** | `30.02 s` / `31.57 s` | **`5.7 ms` / `12.57 ms`** | — |
| **Legitimate Pass Rate (`qa1_legitimate_pass_rate`)** | `39.02%` (`64 / 164`) | **`100.00%` (`5,977 / 5,977`)** | `>= 95%` |
| **HTTP Failed Rate (`http_req_failed`)** | `59.76%` (`101 / 169`) | **`0.00%` (`0 / 5,982`)** | — |
| **Dropped Iterations** | `5,814` | **`23`** | — |
| **Peak Active k6 VUs** | `100 / 100` (saturated) | **`1–2 / 52`** (steady `100.00 iters/s`) | — |
| **Idle `agentgate-app` Memory (`docker stats`)** | `1016 MiB` (`99.26%` of `1 GiB`) | **`450 MiB`** (`43.94%` of `1 GiB`) | `< 1,024 MiB` |

```mermaid
xychart-beta
    title "QA2 Sustained 100 RPS Optimized Latency Breakdown (ms)"
    x-axis ["Overhead med", "Overhead avg", "Overhead p90", "Overhead p95", "Overhead SLA", "HTTP p95", "HTTP SLA"]
    y-axis "Latency (ms)" 0 --> 45
    bar [1, 3.01, 3, 5, 30, 17.29, 40]
```

```mermaid
xychart-beta
    title "QA2 Failure & Pool Saturation Comparison (%)"
    x-axis ["Pass Rate Before", "Pass Rate After", "HTTP Fail Before", "HTTP Fail After"]
    y-axis "Percentage (%)" 0 --> 105
    bar [39.02, 100.0, 59.76, 0.0]
```

### 3.4 Raw k6 Output — Failed Run #1
```text
     scenarios: (100.00%) 1 scenario, 100 max VUs, 1m30s max duration (incl. graceful stop):
              * sustained_100_rps: 100.00 iterations/s for 1m0s (maxVUs: 30-100, gracefulStop: 30s)

  █ THRESHOLDS

    agentgate_gateway_overhead_ms
    ✗ 'p(95)<30' p(95)=59.88s

    http_req_duration
    ✗ 'p(95)<40' p(95)=59.88s

    qa1_legitimate_pass_rate
    ✗ 'rate>=0.95' rate=39.02%

  █ TOTAL RESULTS

    checks_total.......: 328    3.614401/s
    checks_succeeded...: 39.02% 128 out of 328
    checks_failed......: 60.97% 200 out of 328

    CUSTOM
    agentgate_gateway_overhead_ms...: avg=25.76s min=8ms      med=29.97s   max=1m0s   p(90)=59.87s p(95)=59.88s
    qa1_legitimate_pass_rate........: 39.02% 64 out of 164

    HTTP
    http_req_duration...............: avg=31.57s min=100.8ms  med=30.02s   max=1m0s   p(90)=59.87s p(95)=59.88s
    http_req_failed.................: 59.76% 101 out of 169
    http_reqs.......................: 169    1.862298/s
    dropped_iterations..............: 5814   64.067458/s
```

### 3.5 Raw k6 Output — Optimized Run #2 (`Command exited with code 0`)
```text
     scenarios: (100.00%) 1 scenario, 100 max VUs, 1m30s max duration (incl. graceful stop):
              * sustained_100_rps: 100.00 iterations/s for 1m0s (maxVUs: 30-100, gracefulStop: 30s)

  █ THRESHOLDS

    agentgate_gateway_overhead_ms
    ✓ 'p(95)<30' p(95)=5ms

    http_req_duration
    ✓ 'p(95)<40' p(95)=17.29ms

    qa1_legitimate_pass_rate
    ✓ 'rate>=0.95' rate=100.00%

  █ TOTAL RESULTS

    checks_total.......: 11954   192.215958/s
    checks_succeeded...: 100.00% 11954 out of 11954
    checks_failed......: 0.00%   0 out of 11954

    ✓ status is 200
    ✓ outcome is EXECUTED

    CUSTOM
    agentgate_gateway_overhead_ms...: avg=3.01ms  min=0s     med=1ms    max=370ms   p(90)=3ms     p(95)=5ms
    qa1_legitimate_pass_rate........: 100.00% 5977 out of 5977

    HTTP
    http_req_duration...............: avg=12.57ms min=3.4ms  med=5.7ms  max=1.19s   p(90)=11.08ms p(95)=17.29ms
    http_req_failed.................: 0.00%   0 out of 5982
    http_reqs.......................: 5982    96.188377/s
    dropped_iterations..............: 23      0.369832/s
    iterations......................: 5977    96.107979/s
    vus.............................: 1       min=0            max=42
    vus_max.........................: 52      min=30           max=52
```

---

## 4. QA1 Live Red-Team Security Suite (`grafana/k6`)

Executed [qa1_security_suite.k6.js](../load-tests/qa1_security_suite.k6.js) against the optimized Docker stack (`support-bot` OAuth2 JWT + 200 legitimate tool calls + 30 red-team attacks: policy limit breach `amount=50000`, cross-customer tenant read `cust-999`, prompt injection / SQL smuggling, unauthorized `bulk_payout`):

```text
  █ THRESHOLDS

    qa1_legitimate_pass_rate
    ✓ 'rate>=0.95' rate=100.00%

    qa1_malicious_blocked_rate
    ✓ 'rate==1.0' rate=100.00%

  █ TOTAL RESULTS

    checks_total.......: 1       0.359111/s
    checks_succeeded...: 100.00% 1 out of 1
    checks_failed......: 0.00%   0 out of 1

    ✓ QA1 suite completed

    CUSTOM
    qa1_legitimate_pass_rate.......: 100.00% 200 out of 200
    qa1_malicious_blocked_rate.....: 100.00% 30 out of 30

    HTTP
    http_req_duration..............: avg=11.32ms min=3.77ms med=6.84ms max=196.99ms p(90)=8.84ms p(95)=42.99ms
      { expected_response:true }...: avg=10.86ms min=5.02ms med=7.02ms max=153.66ms p(90)=8.76ms p(95)=28.44ms
    http_req_failed................: 12.08%  29 out of 240
```

> *Note:* `http_req_failed` (`29 / 240`) reflects expected `400`/`403`/`422` ProblemDetail HTTP rejections for the malicious payloads + already-registered `support-bot` setup check; `bulk_payout` returns HTTP `202` (`HELD_FOR_APPROVAL`).

---

## 5. Container Memory Budget (`docker stats`, Requirement C1)

Captured across all 4 Docker Compose services (`1.75 GiB` compose limits on core stack, `+32 MiB` mock downstream):

| Container | Compose Limit | ZGC Idle (Before) | G1GC Idle (After) | G1GC Post-Load (`6,207` calls) |
| :--- | :---: | :---: | :---: | :---: |
| `agentgate-app` | `1 GiB` (`1024 MiB`) | `1016 MiB` (`99.26%`) | **`450 MiB`** (`43.94%`) | **`627.3 MiB`** (`61.26%`) |
| `agentgate-postgres` | `512 MiB` | `31.89 MiB` (`6.23%`) | `55.53 MiB` (`10.85%`) | `67.14 MiB` (`13.11%`) |
| `agentgate-redis` | `256 MiB` | `4.715 MiB` (`1.84%`) | `10.88 MiB` (`4.25%`) | `10.25 MiB` (`4.00%`) |
| `agentgate-mock-downstream` | `32 MiB` | `6.941 MiB` (`21.69%`) | `7.387 MiB` (`23.08%`) | `7.613 MiB` (`23.79%`) |
| **Stack Total** | **`1,824 MiB`** | **`1,059.5 MiB`** | **`523.8 MiB`** | **`712.3 MiB`** (**8.7%** of 8 GB) |

```mermaid
xychart-beta
    title "Container Memory Usage (MiB) vs 1 GiB App Limit"
    x-axis ["App ZGC Idle", "App G1GC Idle", "App G1GC Post-Load", "Postgres", "Redis", "Mock Downstream"]
    y-axis "Memory (MiB)" 0 --> 1050
    bar [1016, 450, 627.3, 67.14, 10.25, 7.61]
```

Container log audit across the entire run (`docker logs agentgate-app`) confirmed **0 `ERROR` logs**, **0 HikariCP connection timeouts**, and **0 duplicate-sequence exceptions**.

---

## 6. Live QA3 Audit Verification, Prometheus Metrics & QA5 OpenAPI Import

### 6.1 `GET /api/v1/audit/verify` & Hash Chain Linkage (QA3)
Every one of the `5,977` QA2 + `230` QA1 (`6,207` total) tool calls flushed cleanly and verified cryptographically intact:
```json
{
  "intact": true,
  "totalRecordsVerified": 6207,
  "firstTamperedSequenceNumber": null,
  "firstTamperedEntryId": null,
  "failureReason": "All hash-chained audit records verified intact",
  "verifiedAt": "2026-11-17T12:07:14.512897937Z"
}
```
Verified consecutive chain linkage (`GET /api/v1/audit?agentClientId=support-bot&page=0&size=2`):
- `sequenceNumber: 6206` -> `entryHash: "eb0af1a45403a157b2670a4f3df73494d111f1086adab0935f89c90421affb6a"`
- `sequenceNumber: 6207` -> `previousHash: "eb0af1a45403a157b2670a4f3df73494d111f1086adab0935f89c90421affb6a"`

### 6.2 Live Actuator Health, Micrometer Prometheus & OpenAPI Docs
- `GET /actuator/health`: `{"groups": ["liveness", "readiness"], "status": "UP"}`
- `GET /actuator/metrics/agentgate.gateway.overhead` (`auth`): `COUNT = 6207.0`, `TOTAL_TIME = 23.981467931s` (**`3.86 ms` global average**)
- `GET /actuator/prometheus` outcome breakdown (`6,207` total):
  - `agentgate_gateway_calls_total{outcome="EXECUTED",tool="lookup_order"} 6077.0`
  - `agentgate_gateway_calls_total{outcome="EXECUTED",tool="issue_refund"} 100.0`
  - `agentgate_gateway_calls_total{outcome="DENIED_BY_POLICY",tool="issue_refund"} 10.0`
  - `agentgate_gateway_calls_total{outcome="DENIED_BY_POLICY",tool="lookup_order"} 10.0`
  - `agentgate_gateway_calls_total{outcome="REJECTED_BY_GUARD",tool="lookup_order"} 8.0`
  - `agentgate_gateway_calls_total{outcome="HELD_FOR_APPROVAL",tool="bulk_payout"} 2.0`
- `GET /v3/api-docs`: OpenAPI `3.1.0` serving **`22` documented paths**.

### 6.3 Live `POST /api/v1/tools/import-openapi` (QA5)
Imported `qa5_get_order` from an OpenAPI `3.0.1` JSON payload targeting `http://mock-downstream:8081`:
```json
{
  "name": "qa5_get_order",
  "description": "Get order details",
  "riskLevel": "LOW",
  "owningTeam": "commerce-team",
  "toolType": "REST",
  "status": "ENABLED",
  "writeOperation": false,
  "downstreamUrl": "http://mock-downstream:8081/orders/{id}",
  "httpMethod": "GET",
  "timeoutMs": 2000,
  "downstreamHealthy": true,
  "circuitBreakerState": "CLOSED"
}
```

---

## 7. Live Re-run After AI-Usage Log Cases 11–14 (2026-11-18)

Stack rebuilt from `main` at `bd75f02` with `docker compose up -d --build` and restarted after the console
passwords were changed in the git-ignored `.env`: `docker` profile active, every secret supplied by `.env`
(no repository defaults, C2), and the new `V7__approval_execution_status` / `V8__tool_approval_webhook_url`
migrations applied cleanly to the existing `postgres_data` volume. The audit HMAC key and database password
were kept from the volume's first start so the existing hash chain stays verifiable. Same k6 scripts and
mock downstream as §3–§4, run from a `grafana/k6` container on the Compose network.

### 7.1 Run Log (including failed runs)

| # | Run | Result | Key Numbers |
| :---: | :--- | :---: | :--- |
| A | QA2, JVM just restarted | **FAIL** (k6 exit `99`) | overhead `p(95) = 135.79 ms`, HTTP `p(95) = 438.49 ms`, `5,933 / 5,933` pass, `67` dropped iterations |
| B | QA2, JVM warm | **FAIL** (k6 exit `99`) | overhead `p(95) = 15 ms` (QA2 met), HTTP `p(95) = 468.86 ms` (script's 40 ms end-to-end bound missed), `376` dropped iterations |
| C | QA1 | **FAIL** (k6 exit `99`) | malicious `30 / 30` blocked, legitimate only `100 / 200` (`DAILY_QUOTA`) |
| D | QA2, after the fixes in §7.2 | **PASS** (k6 exit `0`) | overhead `p(95) = 1 ms`, HTTP `p(95) = 5.97 ms`, `6,000 / 6,000` pass, `0` dropped iterations |
| E | QA1, after the fixes in §7.2 | **PASS** (k6 exit `0`) | malicious `30 / 30` blocked/held, legitimate `200 / 200` |

```mermaid
xychart-beta
    title "QA2 p95 Across 2026-11-18 Re-runs (ms)"
    x-axis ["A overhead", "B overhead", "D overhead", "Overhead SLA", "A HTTP", "B HTTP", "D HTTP", "HTTP SLA"]
    y-axis "Latency (ms)" 0 --> 500
    bar [135.79, 15, 1, 30, 438.49, 468.86, 5.97, 40]
```

### 7.2 Root Causes and What Was Changed

1. **Runs A and B — host memory pressure, not a code regression.** Gateway time was low while end-to-end time
   spiked (run B: overhead `p(95) = 15 ms` vs HTTP `p(95) = 468.86 ms`). Actuator metrics across both runs showed
   `jvm.gc.pause` `MAX = 1.936 s` for G1 young pauses (target `10 ms`) and `http.client.requests` `MAX = 1.26 s`
   to the nginx mock, with `0` HikariCP waits. The Windows host had only `0.8 GB` of `7.7 GB` RAM free, the
   *Memory Compression* process held `~1 GB`, and the Docker WSL VM's working set was trimmed to `364 MiB`, so
   the JVM heap was being compressed and paged by the host. No request-path code changed in Cases 11–14 for
   low-risk calls. **Change:** closed the web browser (`~1 GB`) so `1.7 GB` was free, then re-ran (run D);
   the recent `jvm.gc.pause` max dropped to `34 ms`.
2. **Run C — FR6 daily quota working as designed.** QA1's setup sets `support-bot` / `lookup_order` to
   `10,000` calls per day, and runs A and B had already made `11,558` such calls that day (the Redis counter
   read `11,668` after run C's own attempts); the gateway logged `110` `LIMIT BREACH ALERT ... DAILY_QUOTA
   (limit=10000)` events and answered `429`. Run C's `30 / 30` malicious result is therefore not a clean
   measurement either: its 10 cross-customer reads were stopped by the quota, not by the policy. **Change:**
   deleted only the four `rl:*support-bot*` Redis rate-limit counters (test state that expires within 24 h),
   then ran QA2 before QA1 as in §3–§4 (`6,000` + `110` `lookup_order` calls, well under the quota). No new
   breaches occurred in runs D and E, so run E is the QA1 result.

### 7.3 Raw k6 Output — Run D (QA2, `100 RPS`, `60s`, exit code `0`)
```text
  █ THRESHOLDS

    agentgate_gateway_overhead_ms
    ✓ 'p(95)<30' p(95)=1ms

    http_req_duration
    ✓ 'p(95)<40' p(95)=5.97ms

    qa1_legitimate_pass_rate
    ✓ 'rate>=0.95' rate=100.00%


  █ TOTAL RESULTS

    checks_total.......: 12000   197.980057/s
    checks_succeeded...: 100.00% 12000 out of 12000
    checks_failed......: 0.00%   0 out of 12000

    ✓ status is 200
    ✓ outcome is EXECUTED

    CUSTOM
    agentgate_gateway_overhead_ms...: avg=523.66µs min=0s       med=0s     max=34ms     p(90)=1ms    p(95)=1ms
    qa1_legitimate_pass_rate........: 100.00% 6000 out of 6000

    HTTP
    http_req_duration...............: avg=4.31ms   min=257.16µs med=3.51ms max=165.6ms  p(90)=5.09ms p(95)=5.97ms
      { expected_response:true }....: avg=4.29ms   min=257.16µs med=3.51ms max=161.79ms p(90)=5.09ms p(95)=5.96ms
    http_req_failed.................: 0.01%   1 out of 6005
    http_reqs.......................: 6005    99.07252/s

    EXECUTION
    iteration_duration..............: avg=4.55ms   min=2.66ms   med=3.85ms max=161.98ms p(90)=5.46ms p(95)=6.28ms
    iterations......................: 6000    98.990028/s
    vus.............................: 1       min=1            max=1
    vus_max.........................: 30      min=30           max=30
```
> `http_req_failed` `1 / 6005` is the idempotent setup call re-registering the existing `support-bot` agent;
> all `6,000` gateway calls returned `200` / `EXECUTED`.

### 7.4 Raw k6 Output — Run E (QA1 red team, exit code `0`)
```text
  █ THRESHOLDS

    qa1_legitimate_pass_rate
    ✓ 'rate>=0.95' rate=100.00%

    qa1_malicious_blocked_rate
    ✓ 'rate==1.0' rate=100.00%


  █ TOTAL RESULTS

    checks_total.......: 1       0.543316/s
    checks_succeeded...: 100.00% 1 out of 1
    checks_failed......: 0.00%   0 out of 1

    ✓ QA1 suite completed

    CUSTOM
    qa1_legitimate_pass_rate.......: 100.00% 200 out of 200
    qa1_malicious_blocked_rate.....: 100.00% 30 out of 30

    HTTP
    http_req_duration..............: avg=7.43ms min=1.77ms med=3.66ms max=93.52ms p(90)=5.41ms p(95)=13.4ms
      { expected_response:true }...: avg=7.3ms  min=2.87ms med=3.7ms  max=93.52ms p(90)=5.41ms p(95)=8.93ms
    http_req_failed................: 12.08%  29 out of 240
    http_reqs......................: 240     130.395749/s

    EXECUTION
    iteration_duration.............: avg=1s     min=1s     med=1s     max=1s      p(90)=1s     p(95)=1s
    iterations.....................: 1       0.543316/s
    vus............................: 1       min=1          max=1
    vus_max........................: 1       min=1          max=1
```
> As in §4, `http_req_failed` (`29 / 240`) counts the expected `4xx` ProblemDetail rejections plus the
> already-registered `support-bot` setup call.

### 7.5 Raw k6 Output — Failed Run A (QA2, JVM just restarted, exit code `99`)
```text
  █ THRESHOLDS

    agentgate_gateway_overhead_ms
    ✗ 'p(95)<30' p(95)=135.79ms

    http_req_duration
    ✗ 'p(95)<40' p(95)=438.49ms

    qa1_legitimate_pass_rate
    ✓ 'rate>=0.95' rate=100.00%


  █ TOTAL RESULTS

    checks_total.......: 11866   185.91651/s
    checks_succeeded...: 100.00% 11866 out of 11866
    checks_failed......: 0.00%   0 out of 11866

    ✓ status is 200
    ✓ outcome is EXECUTED

    CUSTOM
    agentgate_gateway_overhead_ms...: avg=19.22ms min=0s       med=1ms    max=851ms p(90)=14ms    p(95)=135.79ms
    qa1_legitimate_pass_rate........: 100.00% 5933 out of 5933

    HTTP
    http_req_duration...............: avg=55.33ms min=292.41µs med=6.61ms max=2s    p(90)=40.69ms p(95)=438.49ms
      { expected_response:true }....: avg=55.08ms min=292.41µs med=6.61ms max=2s    p(90)=40.64ms p(95)=435.72ms
    http_req_failed.................: 0.01%   1 out of 5938
    http_reqs.......................: 5938    93.036595/s

    EXECUTION
    dropped_iterations..............: 67      1.049756/s
    iteration_duration..............: avg=55.27ms min=3.57ms   med=7.04ms max=2s    p(90)=41.13ms p(95)=432.7ms
    iterations......................: 5933    92.958255/s
    vus.............................: 0       min=0            max=75
    vus_max.........................: 94      min=30           max=94
```

### 7.6 Raw k6 Output — Failed Run B (QA2, JVM warm, exit code `99`)
```text
  █ THRESHOLDS

    agentgate_gateway_overhead_ms
    ✓ 'p(95)<30' p(95)=15ms

    http_req_duration
    ✗ 'p(95)<40' p(95)=468.86ms

    qa1_legitimate_pass_rate
    ✓ 'rate>=0.95' rate=100.00%


  █ TOTAL RESULTS

    checks_total.......: 11250   184.833791/s
    checks_succeeded...: 100.00% 11250 out of 11250
    checks_failed......: 0.00%   0 out of 11250

    ✓ status is 200
    ✓ outcome is EXECUTED

    CUSTOM
    agentgate_gateway_overhead_ms...: avg=19.06ms min=0s      med=1ms    max=2.5s  p(90)=5ms     p(95)=15ms
    qa1_legitimate_pass_rate........: 100.00% 5625 out of 5625

    HTTP
    http_req_duration...............: avg=82.97ms min=610.7µs med=5.05ms max=2.6s  p(90)=95.41ms p(95)=468.86ms
      { expected_response:true }....: avg=82.94ms min=610.7µs med=5.05ms max=2.6s  p(90)=93.94ms p(95)=469.34ms
    http_req_failed.................: 0.01%   1 out of 5630
    http_reqs.......................: 5630    92.499044/s

    EXECUTION
    dropped_iterations..............: 376     6.177556/s
    iteration_duration..............: avg=83.77ms min=3.07ms  med=5.45ms max=2.64s p(90)=94.89ms p(95)=479.24ms
    iterations......................: 5625    92.416896/s
    vus.............................: 1       min=0            max=100
    vus_max.........................: 100     min=30           max=100
```

### 7.7 Raw k6 Output — Failed Run C (QA1, daily quota exhausted, exit code `99`)
```text
  █ THRESHOLDS

    qa1_legitimate_pass_rate
    ✗ 'rate>=0.95' rate=50.00%

    qa1_malicious_blocked_rate
    ✓ 'rate==1.0' rate=100.00%


  █ TOTAL RESULTS

    checks_total.......: 1       0.252153/s
    checks_succeeded...: 100.00% 1 out of 1
    checks_failed......: 0.00%   0 out of 1

    ✓ QA1 suite completed

    CUSTOM
    qa1_legitimate_pass_rate.......: 50.00%  100 out of 200
    qa1_malicious_blocked_rate.....: 100.00% 30 out of 30

    HTTP
    http_req_duration..............: avg=16.2ms  min=4.17ms med=8.37ms max=277.98ms p(90)=25.83ms p(95)=85.15ms
      { expected_response:true }...: avg=21.32ms min=5.49ms med=8.71ms max=277.98ms p(90)=59.76ms p(95)=90.27ms
    http_req_failed................: 53.75%  129 out of 240
    http_reqs......................: 240     60.516647/s

    EXECUTION
    iteration_duration.............: avg=2.95s   min=2.95s  med=2.95s  max=2.95s    p(90)=2.95s   p(95)=2.95s
    iterations.....................: 1       0.252153/s
    vus............................: 1       min=0          max=1
    vus_max........................: 1       min=1          max=1
```

### 7.8 Container Memory (`docker stats --no-stream`, C1)

| Container | Compose Limit | Idle (before run A) | Post-Load (after run D) |
| :--- | :---: | :---: | :---: |
| `agentgate-app` | `1 GiB` | `443.7 MiB` (`43.33%`) | `619 MiB` (`60.45%`) |
| `agentgate-postgres` | `512 MiB` | `61.13 MiB` (`11.94%`) | `94.24 MiB` (`18.41%`) |
| `agentgate-redis` | `256 MiB` | `14.03 MiB` (`5.48%`) | `13.32 MiB` (`5.20%`) |
| `agentgate-mock-downstream` | `32 MiB` | `9.359 MiB` (`29.25%`) | `8.656 MiB` (`27.05%`) |
| **Stack Total** | **`1,824 MiB`** | **`528.2 MiB`** | **`735.2 MiB`** (**9.0%** of 8 GB) |

### 7.9 Live Audit Verification and Log Audit (QA3)

All `24,227` hash-chained entries (the `6,209` already in the volume plus every call from runs A–E) verified intact
after the runs:
```json
{"intact":true,"totalRecordsVerified":24227,"firstTamperedSequenceNumber":null,"firstTamperedEntryId":null,"failureReason":"All hash-chained audit records verified intact","verifiedAt":"2026-11-18T17:44:14.519546978Z"}
```
`docker logs agentgate-app` for the whole re-run: **`0` `ERROR` logs**, **`0` HikariCP connection timeouts**,
**`0` duplicate-sequence exceptions**, and exactly the `110` `DAILY_QUOTA` breaches explained in §7.2.

---

## 8. Real-Outage QA4, Live Quality Script and CI (2026-11-19)

Fixes for AI-usage log Cases 15–17: last-known-good caches and a 2-second policy-store probe for QA4, the MCP Java SDK
client for downstream MCP tools with per-agent tool discovery (FR1), and `load-tests/run-live-qa.sh`, which runs the
QA2 and QA1 k6 suites, a QA4 PostgreSQL outage drill (`docker pause agentgate-postgres`) and the QA3 chain verification.
CI runs the same script in the `live-quality-attributes` job against a Docker Compose stack started with a throw-away `.env`.

### 8.1 QA4 With a Real Database Outage

- **Testcontainers** (`qa4RealDatabaseOutageKeepsLowRiskToolsWorkingAndFailsClosed`, `6.717 s`): with the PostgreSQL
  container paused, `QA4 outage: LOW-risk call EXECUTED in 8 ms from cached state`; the `MEDIUM` refund and `HIGH` payout
  failed closed; after unpausing, the outage-time call appeared in the audit search and `verifyChainIntegrity()` stayed intact.
- **Live Docker Compose drill** (`bash load-tests/run-live-qa.sh qa4 qa3`, request times measured by curl):
```text
== qa4: PostgreSQL outage drill (docker pause agentgate-postgres)
policyStore health during the outage: DOWN
PASS  qa4: LOW-risk call during the outage -> 200 EXECUTED in 8 ms
PASS  qa4: MEDIUM-risk call during the outage -> 409 fail closed
PASS  qa4: HIGH-risk call during the outage -> 409 fail closed
PASS  qa4: the outage-time call was hash-chained after recovery (policyStore UP)
== qa3: audit hash chain verification
PASS  qa3: "totalRecordsVerified":30486 intact
== 0 failed check(s); results in build/live-qa/
```
> Before the fix, code review (Case 15) found that the same outage would have made calls wait on Hikari's 30-second
> connection timeout and, once the 10-second caches expired, fail; QA4 had only been verified by flipping a flag.

### 8.2 Full Script Run (`bash load-tests/run-live-qa.sh qa1 qa4 qa3 qa2`)
```text
== qa1: k6 load-tests/qa1_security_suite.k6.js
PASS  qa1: k6 thresholds met
    ✓ 'rate>=0.95' rate=100.00%
    ✓ 'rate==1.0' rate=100.00%
== qa4: PostgreSQL outage drill (docker pause agentgate-postgres)
policyStore health during the outage:
PASS  qa4: LOW-risk call during the outage -> 200 EXECUTED in 290 ms
PASS  qa4: MEDIUM-risk call during the outage -> 409 fail closed
PASS  qa4: HIGH-risk call during the outage -> 409 fail closed
PASS  qa4: the outage-time call was hash-chained after recovery (policyStore )
== qa3: audit hash chain verification
PASS  qa3: "totalRecordsVerified":24467 intact
== qa2: k6 load-tests/qa2_gateway_latency.k6.js
PASS  qa2: k6 thresholds met
    ✓ 'p(95)<30' p(95)=2ms
    ✓ 'p(95)<40' p(95)=9.68ms
    ✓ 'rate>=0.95' rate=100.00%
== 0 failed check(s); results in build/live-qa/
```
The empty `policyStore` value above was a bug in the script, not the gateway: Jackson 3 orders `details` before `status`.
After the parser was fixed, the re-run in §8.1 shows `DOWN` during the outage and `UP` after it. The `290 ms` in this first
run was measured with shell timestamps, which include the cost of starting processes in Git Bash on Windows; §8.1 uses
curl's own request time.

### 8.3 Raw k6 Output — QA2 From the Script Run (exit code `0`)
```text
  █ THRESHOLDS

    agentgate_gateway_overhead_ms
    ✓ 'p(95)<30' p(95)=2ms

    http_req_duration
    ✓ 'p(95)<40' p(95)=9.68ms

    qa1_legitimate_pass_rate
    ✓ 'rate>=0.95' rate=100.00%


  █ TOTAL RESULTS

    checks_total.......: 12002   198.276293/s
    checks_succeeded...: 100.00% 12002 out of 12002
    checks_failed......: 0.00%   0 out of 12002

    ✓ status is 200
    ✓ outcome is EXECUTED

    CUSTOM
    agentgate_gateway_overhead_ms...: avg=1.05ms min=0s     med=1ms    max=22ms     p(90)=2ms    p(95)=2ms
    qa1_legitimate_pass_rate........: 100.00% 6001 out of 6001

    HTTP
    http_req_duration...............: avg=6.06ms min=1.92ms med=5.13ms max=180.73ms p(90)=7.99ms p(95)=9.68ms
      { expected_response:true }....: avg=6.05ms min=1.92ms med=5.13ms max=180.73ms p(90)=7.99ms p(95)=9.67ms
    http_req_failed.................: 0.01%   1 out of 6006
    http_reqs.......................: 6006    99.220748/s

    EXECUTION
    iteration_duration..............: avg=6.33ms min=3.49ms med=5.45ms max=181.29ms p(90)=8.34ms p(95)=10.03ms
    iterations......................: 6001    99.138146/s
    vus.............................: 1       min=0            max=1
    vus_max.........................: 30      min=30           max=30
```

### 8.4 Raw k6 Output — QA1 From the Script Run (exit code `0`)
```text
  █ THRESHOLDS

    qa1_legitimate_pass_rate
    ✓ 'rate>=0.95' rate=100.00%

    qa1_malicious_blocked_rate
    ✓ 'rate==1.0' rate=100.00%


  █ TOTAL RESULTS

    checks_total.......: 1       0.192367/s
    checks_succeeded...: 100.00% 1 out of 1
    checks_failed......: 0.00%   0 out of 1

    ✓ QA1 suite completed

    CUSTOM
    qa1_legitimate_pass_rate.......: 100.00% 200 out of 200
    qa1_malicious_blocked_rate.....: 100.00% 30 out of 30

    HTTP
    http_req_duration..............: avg=21.31ms min=7.38ms med=13.58ms max=357.23ms p(90)=20.72ms p(95)=46.56ms
      { expected_response:true }...: avg=20.45ms min=8.45ms med=13.67ms max=357.23ms p(90)=20.57ms p(95)=38.34ms
    http_req_failed................: 12.08%  29 out of 240
    http_reqs......................: 240     46.168049/s

    EXECUTION
    iteration_duration.............: avg=3.61s   min=3.61s  med=3.61s   max=3.61s    p(90)=3.61s   p(95)=3.61s
    iterations.....................: 1       0.192367/s
    vus............................: 1       min=0          max=1
    vus_max........................: 1       min=1          max=1
```
> QA1's setup now gives `lookup_order` the same daily budget as QA2 (Case 17), so the suites no longer depend on run
> order (compare run C in §7).

## 9. Spring Authorization Server, Token-Bound End Users and FR6 Alerts (2026-11-19)

Fixes for AI-usage log Cases 18–20: limit-breach alerts (FR6), the end user bound to the agent token (FR2, QA1), and
Spring Authorization Server instead of the hand-written token endpoint (FR2, ADR-005). The Docker image was rebuilt and
started on the existing volume (`V9__limit_breach_alerts` applied by Flyway); the automated suite is 36/36 (§2). The
checks below ran against that stack; the admin credentials were read from `.env` and are not shown.

### 9.1 Spring Authorization Server Issues the Agent Tokens (FR2, Case 20)
```text
== 1. Authorization server metadata (RFC 8414)
  issuer = https://agentgate.cse444.com
  token_endpoint = https://agentgate.cse444.com/oauth2/token
  grant_types_supported = ['client_credentials']
  token_endpoint_auth_methods_supported = ['client_secret_basic', 'client_secret_post']
  introspection_endpoint = https://agentgate.cse444.com/oauth2/introspect
  revocation_endpoint = https://agentgate.cse444.com/oauth2/revoke
  jwks_uri advertised: False
== 2. Token from Spring Authorization Server (support-bot acting for cust-42)
  header: {'typ': 'JWT', 'alg': 'HS256'}
  claims: {'iss': 'https://agentgate.cse444.com', 'sub': 'support-bot', 'client_id': 'support-bot', 'clearance_level': 'NONE', 'delegated_user': 'cust-42', 'scope': 'mcp:invoke'} lifetime_s = 3600
== 3. Token introspection (RFC 7662)
  {'active': True, 'client_id': 'support-bot', 'token_type': 'Bearer', 'delegated_user': 'cust-42', 'clearance_level': 'NONE'}
== 4. Wrong client secret
  401
```
The metadata lists only what an agent can use. Spring Authorization Server's defaults also advertised the
authorization-code, refresh-token and token-exchange grants, JWT and mTLS client authentication, PKCE, DPoP and a
`jwks_uri` that answered `401`; the HS256 signing key is secret, so no JWK Set is published.

### 9.2 The End User Is Bound to the Token (FR2, QA1, Case 19)
```text
== 5. Delegated user bound to the token (FR2, QA1)
  own user (cust-42):          200 EXECUTED |
  body names cust-7:           403 Delegated User Mismatch | The request names delegated user 'cust-7', but this agent token was issued for 'cust-42'; request a token for
  header names cust-7:         403 Delegated User Mismatch | The request names delegated user 'cust-7', but this agent token was issued for 'cust-42'; request a token for
```
```text
WARN  GatewayOrchestrationService  Rejected call by agent 'support-bot' to tool 'lookup_order': request names delegated user 'cust-7' but the token is bound to 'cust-42' (FR2)
WARN  GatewayOrchestrationService  Rejected call by agent 'support-bot' to tool 'lookup_order': request names delegated user 'cust-7' but the token is bound to 'cust-42' (FR2)
```
Before this fix, the same body or header would have made the call run for `cust-7` and pass a
`MATCHES_DELEGATED_USER` policy.

### 9.3 FR6 Limit-Breach Alert Drill (Case 18)
A demo agent with `lookup_order` limited to one call per minute, called three times:
```text
== 6. FR6 alert drill (fr6-demo-bot, lookup_order limited to 1 call/minute)
  register fr6-demo-bot: 201
  limit rpm=1: 200
  call 1: 409 Operation Blocked by Governance State | Policy denied tool call: Denied by default-deny policy stance (no matching ALLOW rule found)
  call 2: 429 Rate Limit, Daily Quota, or Write Blast-Radius Cap Exceeded | Agent 'fr6-demo-bot' breached PER_MINUTE_RATE_LIMIT (limit=1) on tool 'lookup_order' (FR6)
  call 3: 429 Rate Limit, Daily Quota, or Write Blast-Radius Cap Exceeded | Agent 'fr6-demo-bot' breached PER_MINUTE_RATE_LIMIT (limit=1) on tool 'lookup_order' (FR6)
  alerts for fr6-demo-bot: 1
    {'agentClientId': 'fr6-demo-bot', 'toolName': 'lookup_order', 'breachType': 'PER_MINUTE_RATE_LIMIT', 'configuredLimit': 1, 'webhookNotified': False, 'raisedAt': '2026-11-18T19:21:17.483027Z'}
WARN  LimitBreachAlertService  [LIMIT BREACH ALERT RAISED] agent='fr6-demo-bot' tool='lookup_order' type=PER_MINUTE_RATE_LIMIT limit=1   (thread virtual-172)
```
The first call is within the limit (and denied by the default-deny policy, since the demo agent has no policy); the two
breaches inside the one-minute cooldown raised exactly one alert, listed at `GET /api/v1/limits/alerts`.
`webhookNotified: false` because `AGENTGATE_LIMIT_ALERT_WEBHOOK_URL` is blank in this `.env`; webhook delivery is
covered by `fr6LimitBreachRaisesAlertOnceAndListsIt`.

### 9.4 Live Quality-Attribute Script (`bash load-tests/run-live-qa.sh`, exit code `0`)
```text
== qa2: k6 load-tests/qa2_gateway_latency.k6.js
PASS  qa2: k6 thresholds met
    ✓ 'p(95)<30' p(95)=2ms
    ✓ 'p(95)<40' p(95)=10.62ms
    ✓ 'rate>=0.95' rate=100.00%
== qa1: k6 load-tests/qa1_security_suite.k6.js
PASS  qa1: k6 thresholds met
    ✓ 'rate>=0.95' rate=100.00%
    ✓ 'rate==1.0' rate=100.00%
== qa4: PostgreSQL outage drill (docker pause agentgate-postgres)
policyStore health during the outage: DOWN
PASS  qa4: LOW-risk call during the outage -> 200 EXECUTED in 13 ms
PASS  qa4: MEDIUM-risk call during the outage -> 409 fail closed
PASS  qa4: HIGH-risk call during the outage -> 409 fail closed
PASS  qa4: the outage-time call was hash-chained after recovery (policyStore UP)
== qa3: audit hash chain verification
PASS  qa3: "totalRecordsVerified":36735 intact
== 0 failed check(s); results in build/live-qa/
```
- **QA2:** `6,001` calls at `99.1` calls/s; gateway overhead `avg = 1.13 ms`, `p(95) = 2 ms`, `max = 35 ms`; HTTP
  `avg = 5.96 ms`, `p(95) = 10.62 ms`; `12,002 / 12,002` checks. `http_req_failed` `1 / 6,006` is the setup call
  re-registering the existing `support-bot`, as in §7.
- **QA1:** `setup()` now gets one token per customer from Spring Authorization Server (`cust-1` … `cust-100`, one
  `http.batch`); `200 / 200` legitimate calls passed with their own customer's token and `30 / 30` red-team calls were
  blocked or held. `http_req_failed` `29 / 339` counts the expected `4xx` rejections, as in §4; the 100 token requests
  all succeeded.
- **QA4:** both agent tokens are requested before `docker pause`, because issuing a token reads the agent from the
  database; the LOW call then ran on cached state.
- **Run conditions:** the host had only about `0.8 GB` of its `7.7 GB` RAM available during the run; container memory
  afterwards was `656.5 MiB` in total (`agentgate-app` `547.1 MiB`, `agentgate-postgres` `83.0 MiB`,
  `agentgate-redis` `15.8 MiB`, `agentgate-mock-downstream` `10.6 MiB`).

## 10. Failed CI Run: QA4 Probe Starved by Spring's Scheduler (2026-11-19, Case 26)

CI run `44509718702` (commit `5b374f9`) passed `build-and-verify` but failed the `live-quality-attributes` job, in
the QA4 drill. Raw output from the job:
```text
== qa4: PostgreSQL outage drill (docker pause agentgate-postgres)
policyStore health during the outage: UP
PASS  qa4: LOW-risk call during the outage -> 200 EXECUTED in 5 ms
FAIL  qa4: MEDIUM-risk call during the outage -> 200 EXECUTED (expected 409 fail closed)
FAIL  qa4: HIGH-risk call during the outage -> 500 (expected 409 fail closed)
PASS  qa4: the outage-time call was hash-chained after recovery (policyStore UP)
```
Gateway log from the same job (`docker compose logs agentgate`, message and thread only):
```text
10:56:57  DataSource health check failed                                              tomcat-handler-6214
10:57:09  DataSource health check failed                                              tomcat-handler-6215
10:57:21  DataSource health check failed                                              tomcat-handler-6216
10:57:22  Health contributor ...DataSourceHealthIndicator (db) took 30002ms to respond  tomcat-handler-6213
10:57:22  An I/O error occurred while sending to the backend.                         scheduling-2
10:57:22  Unexpected error occurred in scheduled task                                 scheduling-2
```
No `Policy store unreachable` line appears during the outage.

**Root cause.** The 2-second probe was a fixed-delay `@Scheduled` method on Spring's shared task scheduler, next to the
database-bound fixed-delay tasks (policy refresh, approval timeout sweep, audit retry flush, MCP tool-list sync). When
PostgreSQL was paused, one of those tasks waited on its socket for the 30-second socket timeout (`scheduling-2`, ended
with an I/O error at 10:57:22) and the probe did not run in that time. The store therefore still counted as reachable:
the `MEDIUM` refund ran on cached state, and the `HIGH` payout failed with `500` while creating its approval. Earlier
local and CI runs had passed because the probe happened to run before a database task took the scheduler.

**Reproduced, then fixed.** `qa4ProbeDetectsOutageWhileSpringSchedulerIsBusy` holds Spring's scheduler with a
blocking fixed-delay task, pauses the PostgreSQL container and waits up to 8 s for the store to count as unreachable:

| Code | Result |
| :--- | :--- |
| Before (probe on Spring's scheduler) | `AssertionError: Condition not met within PT8S` |
| After (probe on its own `policy-store-probe` thread) | `QA4 outage detected in 3234 ms while Spring's scheduler was blocked`; `2448 ms` in the full suite (39/39) |

**Live re-run** with the fix (`bash load-tests/run-live-qa.sh`, exit code `0`):
```text
== qa2: k6 load-tests/qa2_gateway_latency.k6.js
PASS  qa2: k6 thresholds met
    ✓ 'p(95)<30' p(95)=11ms
    ✓ 'p(95)<40' p(95)=36.92ms
    ✓ 'rate>=0.95' rate=100.00%
== qa1: k6 load-tests/qa1_security_suite.k6.js
PASS  qa1: k6 thresholds met
    ✓ 'rate>=0.95' rate=100.00%
    ✓ 'rate==1.0' rate=100.00%
== qa4: PostgreSQL outage drill (docker pause agentgate-postgres)
policyStore health during the outage: DOWN
PASS  qa4: LOW-risk call during the outage -> 200 EXECUTED in 15 ms
PASS  qa4: MEDIUM-risk call during the outage -> 409 fail closed
PASS  qa4: HIGH-risk call during the outage -> 409 fail closed
PASS  qa4: the outage-time call was hash-chained after recovery (policyStore UP)
== qa3: audit hash chain verification
PASS  qa3: "totalRecordsVerified":42984 intact
== 0 failed check(s); results in build/live-qa/
```
The outage warning now comes from the probe's own thread:
`Policy store unreachable (probe timed out after PT1.5S); MEDIUM/HIGH-risk tools fail closed ...` (`policy-store-probe`).

- **Run conditions:** about `0.75 GB` of the host's `7.7 GB` RAM was free. The QA2 numbers (`p(95) = 11 ms` overhead,
  `36.92 ms` HTTP against a `40 ms` threshold) are higher than the `1–2 ms` of earlier runs; both are within the limits,
  and the cause was not investigated in this run. Container memory afterwards: `agentgate-app` `525.9 MiB`,
  `agentgate-postgres` `76.2 MiB`, `agentgate-redis` `14.7 MiB`, `agentgate-mock-downstream` `10.9 MiB`.

## 11. QA5 as a Live Check, All Five Quality Attributes in One Run (2026-11-19, Case 29)

`run-live-qa.sh qa5` follows Part 1 of `docs/QUICKSTART.md` with curl against the running stack and no code changes:
import a new Invoice API (benchmark mock, `GET /invoices/{invoiceId}`) from its OpenAPI spec as a `LOW`-risk tool, add an
ALLOW policy with a `REGEX` condition on `invoiceId`, get an agent token, call the tool through the gateway, check that
the e-mail in the result is redacted (FR5) and that the tool is listed on `/mcp/tools` (FR1), and time the steps against
the 10-minute limit. It measures the documented path; the person-based measure (a classmate who has not seen the code)
is the trial in `docs/QA5_TRIAL.md`.

The first local run failed on the check itself, not on the gateway: the script expected `200` from the import, which
answers `201 Created` (`FAIL  qa5: import=201`); every other step had passed. After accepting any `2xx`, the full script
(`bash load-tests/run-live-qa.sh`, exit code `0`, the same command CI runs) passed all five checks:
```text
== qa2: k6 load-tests/qa2_gateway_latency.k6.js
PASS  qa2: k6 thresholds met
    ✓ 'p(95)<30' p(95)=5ms
    ✓ 'p(95)<40' p(95)=21.93ms
    ✓ 'rate>=0.95' rate=100.00%
== qa1: k6 load-tests/qa1_security_suite.k6.js
PASS  qa1: k6 thresholds met
    ✓ 'rate>=0.95' rate=100.00%
    ✓ 'rate==1.0' rate=100.00%
== qa4: PostgreSQL outage drill (docker pause agentgate-postgres)
policyStore health during the outage: DOWN
PASS  qa4: LOW-risk call during the outage -> 200 EXECUTED in 12 ms
PASS  qa4: MEDIUM-risk call during the outage -> 409 fail closed
PASS  qa4: HIGH-risk call during the outage -> 409 fail closed
PASS  qa4: the outage-time call was hash-chained after recovery (policyStore UP)
== qa3: audit hash chain verification
PASS  qa3: "totalRecordsVerified":49227 intact
== qa5: put a new REST tool behind the gateway (OpenAPI import, risk level, policy), no code changes
PASS  qa5: import (LOW risk) + policy + governed call in 1 s with no code changes (limit 600 s)
== 0 failed check(s); results in build/live-qa/
```
The governed call's result, with the customer's e-mail redacted for the `NONE`-clearance support bot:
```text
{"invoiceId":"INV-2026-001","customerEmail":"[REDACTED_EMAIL]","amount":1250,"currency":"BDT","status":"PAID"}
```
