# AgentGate — 10-Minute Developer Quick-Start Guide (QA5) & AI-Usage Log

## Part 1: Put a New REST Tool Behind AgentGate in Under 10 Minutes (Zero Code Changes — QA5)

### Step 1: Start the Full Stack (< 8 GB RAM)
```bash
cp .env.example .env          # then replace every change_me value (secrets have no defaults, C2)
docker compose up -d          # compose stops with a clear message if a required secret is missing

# Load the same values into your shell for the curl commands below
set -a; . ./.env; set +a
ADMIN="$AGENTGATE_ADMIN_USERNAME:$AGENTGATE_ADMIN_PASSWORD"
```
> Postgres keeps the password from its first start inside the `postgres_data` volume. If you change
> `POSTGRES_PASSWORD` later, run `docker compose down -v` once (this deletes local data).
>
> Local run without Docker for the app: `docker compose up -d postgres redis && ./gradlew bootRun`
> (the default `dev` profile reads `.env` from the project root).

### Step 2: Import Your REST Service from an OpenAPI Spec (FR1)
```bash
curl -X POST http://localhost:8080/api/v1/tools/import-openapi \
  -u "$ADMIN" \
  -H "Content-Type: application/json" \
  -d '{
    "owningTeam": "PaymentsTeam",
    "defaultRiskLevel": "MEDIUM",
    "baseUrlOverride": "http://host.docker.internal:8081",
    "defaultTimeoutMs": 2000,
    "approvalWebhookUrl": null,
    "openApiContent": "openapi: 3.0.3\ninfo:\n  title: Refund API\n  version: 1.0.0\npaths:\n  /refunds:\n    post:\n      operationId: issue_refund\n      summary: Issue refund up to policy cap\n      requestBody:\n        required: true\n        content:\n          application/json:\n            schema:\n              type: object\n              properties:\n                orderId:\n                  type: string\n                amount:\n                  type: number\n              required: [orderId, amount]\n      responses:\n        \"200\":\n          description: OK"
  }'
```

> `approvalWebhookUrl` (optional) is where approvers are notified when a HIGH-risk call to the
> imported tools pauses (FR4). It is set by the administrator here, never by the agent; when it is
> `null`, `AGENTGATE_APPROVAL_WEBHOOK_URL` from `.env` is used.

### Step 3: Register Your AI Agent as an OAuth2 Client (FR2)
```bash
curl -X POST http://localhost:8080/api/v1/agents \
  -u "$ADMIN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "support-bot",
    "clientSecret": "support-secret-2026",
    "agentName": "Customer Support Agent",
    "owningTeam": "SupportOps",
    "clearanceLevel": "NONE",
    "scopes": "mcp:invoke"
  }'
```

### Step 4: Attach a Versioned Policy Rule (Hot-Reloads Within 10 Seconds — FR3)
```bash
curl -X POST http://localhost:8080/api/v1/policies \
  -u "$ADMIN" \
  -H "Content-Type: application/json" \
  -d '{
    "ruleName": "allow-refund-under-2000-bdt",
    "priority": 10,
    "agentPattern": "support-bot",
    "toolPattern": "issue_refund",
    "effect": "ALLOW",
    "dryRun": false,
    "conditions": [
      { "field": "amount", "operator": "LTE", "expectedValue": "2000" }
    ],
    "description": "Allow support-bot to refund up to 2,000 BDT"
  }'
```

### Step 5: Test in the Policy Playground & Execute via OAuth2 JWT (FR2, FR9)
```bash
# 1. Obtain OAuth2 Client Credentials Token
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -d "grant_type=client_credentials&client_id=support-bot&client_secret=support-secret-2026&delegated_user=cust-101" \
  | jq -r .access_token)

# 2. Execute Governed Tool Call
curl -X POST http://localhost:8080/api/v1/gateway/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "toolName": "issue_refund",
    "delegatedUserId": "cust-101",
    "arguments": { "orderId": "ORD-900", "amount": 1500 }
  }'

#    The end user is bound to the token (delegated_user): naming a different user in the body or the
#    X-On-Behalf-Of-User header is rejected with 403, so request one token per user session (FR2)

# 3. HIGH-risk calls return outcome HELD_FOR_APPROVAL with a statusUrl: the agent polls it with
#    its own token until "terminal": true (EXECUTED, EXECUTION_FAILED, REJECTED, TIMED_OUT or
#    CANCELLED_BY_KILL_SWITCH) (FR4)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/gateway/approvals/<approvalId>

# 4. Verify Hash-Chained Audit Trail Integrity (FR7, QA3)
curl -u "$ADMIN" http://localhost:8080/api/v1/audit/verify

# 5. Find one call: the callId in the gateway response is its audit record's id (QA3); the same
#    filters (callId, agentClientId, delegatedUserId, toolName, outcome, policyRuleName) work on /export
curl -u "$ADMIN" "http://localhost:8080/api/v1/audit?callId=<callId>"
```

New and edited tools appear on the MCP endpoint within `agentgate.mcp.tool-sync-interval` (5 s)
without a restart; a tool kill switch withdraws the tool immediately (FR1, FR8, FR10). `GET /mcp/tools`
with the agent's token lists only the tools that agent's policies allow, sorted by name and paged like every
other list endpoint (`?page=0&size=50` by default; the reply carries `tools`, `number`, `size`, `totalElements` and
`totalPages`, C3).

### Optional: Run the Live Quality-Attribute Checks (C5)
```bash
docker compose --profile benchmark up -d --wait   # adds the nginx mock downstream used by the k6 suites
bash load-tests/run-live-qa.sh                    # QA2 + QA1 (k6), QA4 PostgreSQL outage drill, QA3 verify
```
The QA4 drill pauses the `agentgate-postgres` container for a few seconds and unpauses it again.

---

## Part 2: AI-Usage Log (Requirement C11)

- **IDE & Model Used:** Antigravity IDE (Gemini 4 Argon High)
- **Review & Fixes (2026-11-18):** Claude Code (Claude Opus 5.5) — requirement-by-requirement code review against the assignment brief, then the fixes logged from Case 11 onward.
- **Key Prompts Used:**
  1. *Step 1 Prompt:* Project scaffolding with Spring Boot 4.0.0, Java 21 Virtual Threads, Spring AI MCP, Spring Modulith + ArchUnit boundary verification, and `< 8 GB RAM` Docker Compose budget.
  2. *Step 2 Prompt:* Core Domain & Tool Registry (`FR1`, `FR8`, `FR10`) with OpenAPI 3.x importer, Spring AI MCP unified front door, and Resilience4j per-tool circuit breakers and timeouts.
  3. *Step 3 Prompt:* OAuth2 Client Credentials identity (`FR2`), versioned policies with 10-second hot-reload, dry-run mode (`FR3`), `QA4` risk-tiered fail-safe cache, custom `AuthorizationManager`, and Policy Playground (`FR9`).
  4. *Step 4 Prompt:* Ordered pluggable `InputCallGuard` and `OutputResultGuard` beans (`FR5`, `FR6`) for JSON Schema validation, anti-smuggling (`QA1`), clearance-aware PII redaction (e-mail, Bangladeshi phone numbers, 10/13/17-digit NIDs), payload truncation, and Bucket4j/Redis rate limits & write blast-radius caps.
  5. *Step 5 Prompt:* Human-in-the-loop Approval State Machine (`FR4`) with sealed `ApprovalLifecycleState`, timeout auto-rejection, webhook notification, and `FR8` Kill-Switch event listener.
  6. *Step 6 Prompt:* Tamper-evident `HMAC-SHA256` hash-chained Audit Trail (`FR7`, `QA3`) via `@TransactionalEventListener` and front-door `GatewayOrchestrationService`.
  7. *Step 7 Prompt:* JUnit 5 + Testcontainers integration tests (`QA1–QA5`), k6 load test script (`QA2 <= 30ms p95 @ 100 RPS`), C4 diagrams, Pattern Justification Table, 4 ADRs, and Mini-ATAM Utility Tree.

### Recorded AI Error & Fix Log Entries (Requirement C11)
*(As we run tests, live defence drills, or mid-project checkpoint changes, any issue discovered and corrected is logged below in the required tracker format.)*
1. **Case 1 — PII Redaction Ordering Between Bangladeshi Phone Numbers and 10/13-Digit NIDs (`FR5`):**
   - *Issue Found:* If the 10-digit Bangladesh Smart NID regex (`\d{10}`) runs before the Bangladeshi mobile number regex (`01[3-9]\d{8}`), an 11-digit local phone number or hyphenated phone number can be partially matched or misclassified as an NID rather than `[REDACTED_BD_PHONE]`.
   - *Fix Applied:* Ordered `BD_PHONE_PATTERN` replacement ahead of `BD_NID_PATTERN` with strict negative lookbehind/lookahead digit boundaries `(?<!\d)...(?!\d)` in `PiiRedactionOutputGuard` and verified with `GuardAndStatePatternUnitTest#piiRedactionByClearanceLevel`.
2. **Case 2 — Recording Blocked Calls in the Audit Trail When Transactions Roll Back (`FR7`, `QA3`):**
   - *Issue Found:* Using a default `@TransactionalEventListener(phase = BEFORE_COMMIT)` causes audit events published during failed/blocked tool calls (which throw `GuardRejectedException`, `LimitExceededException`, or `IllegalStateException` and roll back the outer transaction) to be discarded with the rollback.
   - *Fix Applied:* Configured `AuditTrailService#recordAuditEvent` with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)` and `@Transactional(propagation = Propagation.REQUIRES_NEW)` so every blocked or failed tool call is permanently committed to the hash chain even when the gateway transaction rolls back.
3. **Case 3 — PostgreSQL `TIMESTAMPTZ` Microsecond Precision vs. JVM Nanosecond `Instant` in Hash-Chain Verification (`QA3`):**
   - *Issue Found:* Computing `entry_hash` using raw `Instant.now()` nanoseconds before saving to PostgreSQL `TIMESTAMPTZ` (microsecond precision) can cause a precision mismatch when re-reading `occurred_at` during `verifyChainIntegrity()`.
   - *Fix Applied:* Normalized `occurredAt` via `.truncatedTo(ChronoUnit.MILLIS)` before both initial hash calculation and verification in `AuditTrailService`.
4. **Case 4 — Spring Boot 4.0 Ecosystem Artifact, Security & Jackson 3 Shifts (`C1`, `C3`):**
   - *Issue Found:* Initial `build.gradle` used Spring Boot 3.x starter names/BOMs (`spring-boot-starter-aop`, `spring-ai-mcp-server-webmvc-spring-boot-starter`, `spring-modulith-bom:1.3.3`, `resilience4j-spring-boot3`, `springdoc:2.8.5`), `AgentGateAuthorizationManager` overrode removed `check(...)` instead of Spring Security 7's `authorize(...)`, Jackson 2 `ObjectMapper` bean was absent under Boot 4's Jackson 3 default, and Spring's built-in `ProblemDetailsExceptionHandler` shadowed `GlobalProblemDetailsHandler` on `MethodArgumentNotValidException`.
   - *Fix Applied:* Upgraded `build.gradle` to Spring Boot 4.0.0 starters (`spring-boot-starter-aspectj`, `spring-boot-starter-flyway`, `spring-boot-starter-webmvc`, `spring-ai-bom:2.0.0`, `spring-modulith-bom:2.0.0`, `resilience4j-spring-boot4:2.4.0`, `springdoc:3.0.0`), updated `AgentGateAuthorizationManager#authorize`, registered a fallback Jackson 2 `ObjectMapper` bean in `AgentGateJacksonConfig`, and added `@Order(Ordered.HIGHEST_PRECEDENCE)` to `GlobalProblemDetailsHandler` (verified via `WebSecurityAndProblemDetailsSliceTest`).
5. **Case 5 — Spring Modulith 2.0 `@NamedInterface` Dependency Syntax (`C6`):**
   - *Issue Found:* Running `ArchitectureRulesTest#verifiesSpringModulithBoundaries` failed with 20 violations because `allowedDependencies` listed `"registry"`, `"policy"`, etc. instead of explicit named interfaces when subpackages carried `@NamedInterface`.
   - *Fix Applied:* Updated `approval`, `guard`, `policy`, and `gateway` `package-info.java` descriptors to use explicit `module :: interface` targets (`"registry :: service"`, `"policy :: dto"`, etc.), achieving 6/6 green tests in `ArchitectureRulesTest`.
6. **Case 6 — RBAC Escalation Prevention & MCP Callback Governance Bypass (`FR1`, `FR2`, `FR3`):**
   - *Issue Found:* `.anyRequest().authenticated()` without role restrictions allowed a valid agent OAuth2 JWT to call admin/approval endpoints, no `UserDetailsService` was configured for basic-auth roles, and `McpUnifiedEndpointController` exposed `ToolCallbackProvider` calling `ToolRegistryService` directly rather than `GatewayOrchestrationService`.
   - *Fix Applied:* Configured env-backed `UserDetailsService` (`ADMIN`, `APPROVER`, `AUDITOR`), enforced URL + `@PreAuthorize` RBAC with RFC 9457 `401`/`403` `ProblemDetail` handlers, and moved the MCP `ToolCallbackProvider` bridge into `gateway.service.McpToolCallbackBridge` so every MCP call traverses the full governance pipeline.
7. **Case 7 — `Dockerfile` Gradle Version Mismatch with Spring Boot 4.0 (`C1`):**
   - *Issue Found:* `Dockerfile` used `gradle:8.11-jdk21-alpine`, whereas Spring Boot 4.0 requires Gradle 8.14+ or 9.x.
   - *Fix Applied:* Generated the project Gradle 9.2.0 wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper`) and updated `Dockerfile` to build directly with `./gradlew bootJar --no-daemon -x test` on `eclipse-temurin:21-jdk-alpine`.
8. **Case 8 — Spring Modulith 2.0 Event Schema, Jackson 3 JSON Schema Validator & Swagger JAXB (`C5`, `C6`, `FR5`):**
   - *Issue Found:* During full Testcontainers integration startup against PostgreSQL 16 and Redis 7.4, Hibernate `ddl-auto: validate` flagged missing `completion_attempts` / `status` / `last_resubmission_date` columns and `event_publication_archive` table required by Spring Modulith 2.0; pinning `json-schema-validator:1.5.6` downgraded Spring AI 2.0 MCP SDK's `3.0.0` dependency (`NoClassDefFoundError: com/networknt/schema/dialect/Dialects`); `/v3/api-docs` threw `NoClassDefFoundError: javax/xml/bind/annotation/XmlElement` from `swagger-parser`'s `swagger-core`; and `RateLimitAndBlastRadiusGuard` resolved per-tool vs. agent-wide write blast-radius caps inconsistently across Bucket4j and Redis.
   - *Fix Applied:* Added [V6__update_modulith_event_publication.sql](file:///e:/Assignment/src/main/resources/db/migration/V6__update_modulith_event_publication.sql), upgraded `json-schema-validator` to `3.0.0` (`SchemaRegistry` + `Dialects.getDraft202012()` in `JsonSchemaValidationGuard`), added `javax.xml.bind:jaxb-api:2.3.1`, and unified `resolveAgentWriteBlastCap` in `RateLimitAndBlastRadiusGuard`.
9. **Case 9 — Spring Boot 4.0 Runtime `RestClient.Builder` Bean Missing in Standalone `bootJar` (`C1`, `FR4`):**
   - *Issue Found:* Slice and integration tests passed because `spring-boot-starter-webmvc-test` brought in `RestClient` support classes transitively, whereas launching the compiled Docker `bootJar` failed during startup because `ApprovalWebhookNotifier` could not inject a `RestClient.Builder` bean.
   - *Fix Applied:* Added explicit `implementation 'org.springframework.boot:spring-boot-starter-restclient'` in `build.gradle` and verified clean Docker Compose health check startup alongside `25/25` green Gradle tests.
10. **Case 10 — QA2 100 RPS HikariCP Deadlock, Audit Sequence Collision & ZGC Memory Exhaustion (`QA2`, `FR7`, `C1`):**
    - *Issue Found:* Initial live k6 `100 RPS` run (`load-tests/qa2_gateway_latency.k6.js`) failed all thresholds (`agentgate_gateway_overhead_ms p(95)=59.88s`, `http_req_duration p(95)=59.88s`, `39.02%` pass rate, `5,814` dropped iterations, `1016 MiB / 1 GiB` app RAM). Container logs revealed `HikariPool-1` exhaustion (`total=20, active=20, idle=0, waiting=10`) from holding `@Transactional` across `GatewayOrchestrationService#processToolCall` during downstream I/O while `AFTER_COMPLETION` `REQUIRES_NEW` audit listeners waited for connections, `synchronized` inside `@Transactional` proxy causing `PSQLException: duplicate key value violates unique constraint "audit_log_entries_sequence_number_key"`, 5 redundant per-request SQL lookups, and `-XX:+UseZGC` retaining ~99% of container RAM.
    - *Fix Applied:* Removed outer `@Transactional` from `GatewayOrchestrationService#processToolCall`, added 10s Caffeine caches (invalidated on writes) in `AgentIdentityService`, `ToolRegistryService`, and `RateLimitAndBlastRadiusGuard` plus lock-free `AtomicBoolean` reads in `isAllWriteToolsKilled()`, refactored `AuditTrailService` to a lock-free `ConcurrentLinkedQueue` + dedicated single writer thread + `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` batching up to `128` rows with `flushPendingAudits()` before queries/verification/shutdown, and switched `Dockerfile` JVM flags to `-XX:+UseG1GC -Xms128m -Xmx512m -XX:MaxGCPauseMillis=10`. Re-running QA2 achieved **`p(95) = 5 ms` gateway overhead**, **`p(95) = 17.29 ms` end-to-end HTTP**, **`100.00%` (`5,977/5,977`) pass rate**, **`0` errors**, `6,207` verified intact audit entries, and `450 MiB` idle / `627.3 MiB` post-load RAM.
11. **Case 11 — Agent Never Received the Final Result of a Held High-Risk Call (`FR4`):**
    - *Issue Found:* Code review against FR4 ("the agent receives a clear result in every case") showed that the only approval status endpoint, `GET /api/v1/approvals/{id}`, is restricted to `ADMIN`/`APPROVER`, so the agent that made a held call could never learn its outcome; and an approved call whose downstream failed threw inside `@Transactional`, rolling the decision back so the request stayed `PENDING` until it timed out.
    - *Fix Applied:* Added the agent-scoped `GET /api/v1/gateway/approvals/{approvalId}` (`GatewayController` → `GatewayOrchestrationService#getApprovalResultForAgent` → `ApprovalWorkflowService#getApprovalForRequestingAgent`; other agents get `404`), a `statusUrl` in the `HELD_FOR_APPROVAL` payload, and `AgentApprovalResultResponse` with an explicit `resultStatus`/`terminal`. An `execution_status` column (`V7`) records a failed downstream call as `EXECUTION_FAILED` instead of rolling back, and `approveRequest` flushes the decision before calling downstream so the `@Version` lock stops a concurrent second approval from executing a payout twice. Covered by `fr4AgentPollsFinalApprovalResult`, `fr4ApprovedCallWithFailingDownstreamReportsExecutionFailed` and `agentJwtCanPollApprovalResultButAnonymousCannot`.
12. **Case 12 — Approver Webhook URL Supplied by the Agent (`FR4`, `QA1`):**
    - *Issue Found:* `GatewayToolCallRequest.approvalWebhookUrl` let the calling agent choose where the gateway POSTs approval notifications, so a prompt-injected agent could exfiltrate unredacted arguments or make the gateway call internal hosts (SSRF); without that field no approver webhook was sent at all, approvers were never told how a request ended, and `agentgate.approval.webhook-timeout` was never applied.
    - *Fix Applied:* Removed the field from the agent request; the URL now comes from the administrator-set `approvalWebhookUrl` on the tool (`RegisterToolRequest` / `OpenApiImportRequest`, `V8`) or `AGENTGATE_APPROVAL_WEBHOOK_URL`. `ApprovalWebhookNotifier` redacts arguments, applies the configured timeout, sends only after the transaction commits, and adds an `approval.resolved` webhook for approve, reject, timeout and kill-switch outcomes. Covered by `fr4ApproverWebhooksUseToolConfiguredUrlWithRedactedArguments`.
13. **Case 13 — MCP Tool List Frozen at Startup (`FR1`, `FR8`, `FR10`, `QA5`):**
    - *Issue Found:* Spring AI reads `ToolCallbackProvider` beans once at startup, so tools registered or imported later never appeared on the MCP endpoint and killed or circuit-open tools stayed listed until a restart; only the custom REST `/mcp/tools` view was live.
    - *Fix Applied:* Replaced the startup-only provider with `McpToolListSynchronizer`, which reconciles the live `McpSyncServer` (`addTool`/`removeTool`, which emit `notifications/tools/list_changed`) every `agentgate.mcp.tool-sync-interval` and immediately on tool/write kill switches. Covered by `fr1McpServerToolListFollowsRegistryAtRuntime`.
14. **Case 14 — Default Secrets in the Repository and Missing Profiles (`C2`):**
    - *Issue Found:* `application.yml` and `docker-compose.yml` shipped working fallback secrets (admin password `password`, dev JWT/HMAC keys), `SPRING_PROFILES_ACTIVE=docker` referred to a profile that had no configuration, and `SecurityConfig` silently zero-padded short JWT keys.
    - *Fix Applied:* Removed every secret default; added `dev` (default), `docker` and `test` profiles; the base config imports the git-ignored `.env`; `AgentGateProperties` validates secret length (`@Size(min = 32)` for HMAC keys, `min = 8` for passwords); Compose uses `${VAR:?message}` for required secrets; k6 scripts and the dashboard no longer embed `user/password`.
15. **Case 15 — QA4 Fail-Safe Only Worked in a Simulated Outage (`QA4`, `FR7`):**
    - *Issue Found:* Code review showed the QA4 test only flipped a flag. In a real PostgreSQL outage the agent, tool and limit caches expired after 10 s and `@Transactional` reads needed a connection even on cache hits, so LOW-risk calls blocked on Hikari's 30 s connection timeout and then failed instead of running for 5 minutes; MEDIUM/HIGH detection waited for the next 10 s policy refresh; and failed audit batches were dropped.
    - *Fix Applied:* Added `FailSafeCaches` last-known-good caches (background refresh, served during the outage, expiring after the 5-minute fail-safe TTL) to `AgentIdentityService`, `ToolRegistryService` and `RateLimitAndBlastRadiusGuard`; removed the transactions from the cached reads; added a bounded 2 s policy-store probe and a `policyStore` health component; set Hikari's connection timeout to 2 s with a 30 s socket timeout; and made `AuditTrailService` keep failed batches in order and retry them. Covered by `qa4RealDatabaseOutageKeepsLowRiskToolsWorkingAndFailsClosed`, which pauses the PostgreSQL container (LOW-risk call in 8 ms, MEDIUM/HIGH fail closed, outage-time call audited after recovery).
16. **Case 16 — Downstream MCP Calls Skipped the MCP Protocol and Discovery Ignored Permissions (`FR1`):**
    - *Issue Found:* `McpDownstreamToolAdapter` POSTed a bare JSON-RPC `tools/call` without the `initialize` handshake, so it only worked against the test mock, and the Spring AI MCP client starter was never used; `GET /mcp/tools` listed every healthy tool to every agent instead of the tools each agent is permitted to call.
    - *Fix Applied:* The adapter now uses the MCP Java SDK client (`McpClient.sync`, Streamable HTTP or SSE transport, one initialized session per downstream URL, reconnect after a transport error), and the test mock is a minimal Streamable HTTP MCP server that records the handshake. `McpUnifiedEndpointController` moved to the gateway module and filters by `PolicyEngineService#isToolPermittedForAgent`; administrators still see every tool. The MCP SDK's `tools/list` is server-wide, so there permissions are enforced at call time. Covered by `fr1McpDownstreamFr10CircuitBreakerAndObservabilityEndpoints` and `fr1McpToolsListsOnlyToolsTheAgentIsPermittedToCall`.
17. **Case 17 — CI Did Not Run the Quality-Attribute Scripts (`C5`):**
    - *Issue Found:* C5 asks that every quality-attribute measure runs as a script and that "CI runs them all", but the workflow only ran `./gradlew test`; the k6 suites were run by hand, and QA1 failed when run after QA2 on the same day because its setup set a 10,000-call daily quota.
    - *Fix Applied:* Added `load-tests/run-live-qa.sh` (QA2 and QA1 k6 suites, the QA4 PostgreSQL outage drill with `docker pause`, and the QA3 chain verification) and a `live-quality-attributes` CI job that starts the Docker Compose stack with a throw-away `.env` and runs it; QA1 now sets the same `lookup_order` budget as QA2 so the suites can run in any order. Also moved the workflow to `actions/checkout@v5` and `actions/setup-java@v5`.
18. **Case 18 — Limit Breaches Raised No Alert (`FR6`):**
    - *Issue Found:* FR6 says a breach "blocks further calls and raises an alert", but `RateLimitAndBlastRadiusGuard` only logged a warning, counted a metric and published `LimitBreachAlertEvent`, and nothing listened to that event, so nobody was alerted.
    - *Fix Applied:* `LimitBreachAlertService` (`@EventListener`) raises at most one alert per agent, tool and breach type per `agentgate.limits.alert-cooldown` (1 m), records it (`V9__limit_breach_alerts.sql`, `GET /api/v1/limits/alerts`, paginated) and posts it to `AGENTGATE_LIMIT_ALERT_WEBHOOK_URL`, off the request thread. Covered by `fr6LimitBreachRaisesAlertOnceAndListsIt` (two breaches in the cooldown, one alert and one webhook).
19. **Case 19 — The Agent Could Choose the End User Per Call (`FR2`, `QA1`):**
    - *Issue Found:* `GatewayController` took the delegated user from the request body, then the `X-On-Behalf-Of-User` header, then the token, so a prompt-injected agent could name the victim customer and satisfy a `MATCHES_DELEGATED_USER` policy.
    - *Fix Applied:* The end user is the token's `delegated_user`; `GatewayOrchestrationService#processAgentCall` rejects a different user in the body or header with `403 Delegated User Mismatch` and audits it as `REJECTED_DELEGATED_USER_MISMATCH`. The k6 QA1 suite now requests one token per customer, and the QA4 drill one token for its outage user. Covered by `fr2DelegatedUserIsBoundToTheAgentToken`.
20. **Case 20 — Hand-Written OAuth2 Token Endpoint Instead of Spring Authorization Server (`FR2`, architecture focus):**
    - *Issue Found:* The architecture focus asks for Spring Security's OAuth2 authorization and resource server, but `/oauth2/token` was a hand-written controller method; the `spring-boot-starter-security-oauth2-authorization-server` dependency was declared but unused.
    - *Fix Applied:* Spring Authorization Server now issues agent tokens (`AuthorizationServerConfig`, `AgentRegisteredClientRepository`, `AgentAccessTokenCustomizer`, bounded `ExpiringOAuth2AuthorizationService`), with introspection, revocation and metadata endpoints; the RFC 8414 metadata lists only the `client_credentials` grant and client-secret authentication that agents can use. Tokens stay HS256 for the existing resource server, so Boot's generated RSA JWK auto-configuration is excluded and no JWK Set is published. Recorded as ADR-005; covered by `fr2SpringAuthorizationServerIssuesAgentTokens`.
21. **Case 21 — Unreadable Policy Conditions Made a Rule Unconditional (`FR3`):**
    - *Issue Found:* While preparing the C12 defence, a review showed that `PolicyEngineService#parseConditions` returned an empty list when the stored `conditions_json` could not be read, for example after an operator is renamed (`LTE` to `LESS_OR_EQUAL`) or after a direct database edit. The ALLOW rule then had no conditions, so the 2,000 BDT refund cap allowed any amount. A condition without an operator threw a `NullPointerException` during evaluation instead.
    - *Fix Applied:* Rules are compiled once per policy snapshot. Unreadable or incomplete conditions (missing field, operator or expected value) are logged and mark the rule, which then denies every call that reaches it ("has unreadable conditions; failing closed") and keeps its tool off the agent's `/mcp/tools` list; a dry-run DENY rule still never blocks. The new `PolicyAndConditionUnitTest#unreadableStoredConditionsFailClosed` failed on the old code (a 999,999 BDT refund was allowed) and passes now; full suite 37/37.
22. **Case 22 — The Policy API Accepted Incomplete Conditions (`C3`, `FR3`):**
    - *Issue Found:* `PolicyCondition` had no Bean Validation constraints and the request DTOs did not cascade `@Valid`, so `POST /api/v1/policies` stored a condition without an operator with `201 Created` (after Case 21 that rule only failed closed at evaluation), and a playground draft with such a condition threw a `NullPointerException` instead of a clear error.
    - *Fix Applied:* `field` is `@NotBlank`, `operator` and `expectedValue` are `@NotNull`, and `CreatePolicyRuleRequest.conditions` and `PolicyPlaygroundRequest.draftRules` are `List<@NotNull @Valid ...>`, so both endpoints answer `400 Bean Validation Failed` (RFC 9457) naming the field, for example `conditions[0].operator: must not be null`. The new `WebSecurityAndProblemDetailsSliceTest#incompletePolicyConditionsAreRejectedWith400` failed on the old code (`201`) and passes now; full suite 38/38; checked live.
23. **Case 23 — The Dashboard Playground Could Not Test Draft Policies (`FR9`):**
    - *Issue Found:* FR9 asks to test a sample call "against current or draft policies". The API accepted `draftRules`, but the dashboard's "Test Policy Playground" button always sent the call without them, so drafts could only be tried with curl or Swagger; a rejected playground request (for example `400`) was also shown as `PLAYGROUND: undefined`.
    - *Fix Applied:* The simulator has an optional "Draft rules for the playground" box (a JSON array, never saved) with a sample that lowers the refund cap to 1,000 BDT. When it is filled, the playground evaluates the drafts instead of the active policies and labels the result `PLAYGROUND (DRAFT)` or `PLAYGROUND (ACTIVE)`; a draft without a numeric `priority` is caught in the browser, and server errors such as `draftRules[0].conditions[0].operator: must not be null` are shown. Checked in a browser against the live stack: the 1,200 BDT refund is `ALLOW` by `allow-refund-up-to-2000-bdt@v5` and `DENY` (`DEFAULT_DENY`) with the sample draft.
24. **Case 24 — The Dashboard Could Only Filter the Audit Trail by Agent (`QA3`, `FR7`):**
    - *Issue Found:* QA3 asks that an auditor finds who made a call, on whose behalf, what it did and which rule allowed it in under a minute. The API searched by agent, user, tool, outcome and rule, but the dashboard offered only an agent box and showed the latest 20 rows without the redacted arguments or approval; there was no way to look up the `callId` the agent receives, and the CSV export ignored the rule filter, so a rule-filtered export returned every rule.
    - *Fix Applied:* `GET /api/v1/audit` and `/export` accept `callId` (the gateway's call id is the audit record's id), and the export honours `policyRuleName`. The audit tab has Call ID, agent, user, tool, outcome and rule fields, a match count with paging, a details row (call and trace id, time, approval, redacted arguments, full entry hash), and "Export Matching CSV"; the simulator links a governed call to its audit record. `qa3AuditTrailDetectsTampering` now finds the held call by its `callId` and checks the rule-filtered export; full suite 38/38. Checked in a browser against the live stack (36,764 records, 1 match by call id, paging, combined filters).
25. **Case 25 — The Dashboard Stayed Locked After a Reload (`C3` usability, `FR4`/`FR7` consoles):**
    - *Issue Found:* After a page reload the stored operator session was checked with the empty password box instead of the stored header, so the registry, approval and audit panels stayed locked while playground calls still used the stored header. A stored header the server rejected was never removed, and a wrong password made Chrome show its own login popup, because the API answered with `WWW-Authenticate: Basic`.
    - *Fix Applied:* On load the dashboard checks the stored header itself and reopens the panels; a header the server rejects with `401` is dropped and the health check runs again without it, while an unreachable server keeps the session for the next reload. Every dashboard API call sends `X-Requested-With: XMLHttpRequest`, so a wrong password is answered with a plain `401` and shown as a toast. Checked in a browser against the live stack: unlock, reload (panels reopen), a stale header (dropped, panels locked, "System UP") and a wrong password (toast, no popup).
26. **Case 26 — The QA4 Probe Shared Spring's Scheduler With Tasks That Block on the Database (`QA4`):**
    - *Issue Found:* CI run `44509718702` failed the live QA4 drill: with PostgreSQL paused, `policyStore` stayed `UP` for 30 s, a `MEDIUM` refund executed and a `HIGH` payout returned `500`. The 2-second probe was a fixed-delay `@Scheduled` method on Spring's shared scheduler; a database-bound scheduled task waited there for the 30-second socket timeout, so the probe did not run. Local runs had passed only because the probe usually ran first.
    - *Fix Applied:* The probe runs on a timer thread of its own (`policy-store-probe`), started after the startup policy load; the database call stays on a virtual thread bounded by 1.5 s, and an exception keeps the schedule running and fails closed. The new `qa4ProbeDetectsOutageWhileSpringSchedulerIsBusy` blocks Spring's scheduler and pauses PostgreSQL: it failed on the old code (not detected within 8 s) and passes now (detected in about 2.5–3.5 s); full suite 39/39; the live script passed 4/4. Recorded as ADR-003 Revision 2 and `EVIDENCE_REPORT.md` §10.
27. **Case 27 — `/mcp/tools` Was the One List Endpoint Without Pagination (`C3`, `FR1`):**
    - *Issue Found:* A requirement-by-requirement check against C3 ("pagination on every list endpoint") showed that every list endpoint returned a Spring `Page` except `GET /mcp/tools`, which returned all permitted tools at once.
    - *Fix Applied:* `/mcp/tools` takes `page` and `size` (50 by default), sorts the tools by name so pages are stable, and returns `tools` with `number`, `size`, `totalElements` and `totalPages`; existing clients that read `tools` keep working. `c3McpToolDiscoveryIsPaginated` pages through the list one tool at a time and checks it matches the full list; full suite 40/40.
