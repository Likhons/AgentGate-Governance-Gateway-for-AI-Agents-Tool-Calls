# AgentGate — 10-Minute Developer Quick-Start Guide (QA5) & AI-Usage Log

## Part 1: Put a New REST Tool Behind AgentGate in Under 10 Minutes (Zero Code Changes — QA5)

### Step 1: Start the Full Stack (< 8 GB RAM)
```bash
cp .env.example .env
docker compose up -d
```

### Step 2: Import Your REST Service from an OpenAPI Spec (FR1)
```bash
curl -X POST http://localhost:8080/api/v1/tools/import-openapi \
  -u user:password \
  -H "Content-Type: application/json" \
  -d '{
    "owningTeam": "PaymentsTeam",
    "defaultRiskLevel": "MEDIUM",
    "baseUrlOverride": "http://host.docker.internal:8081",
    "defaultTimeoutMs": 2000,
    "openApiContent": "openapi: 3.0.3\ninfo:\n  title: Refund API\n  version: 1.0.0\npaths:\n  /refunds:\n    post:\n      operationId: issue_refund\n      summary: Issue refund up to policy cap\n      requestBody:\n        required: true\n        content:\n          application/json:\n            schema:\n              type: object\n              properties:\n                orderId:\n                  type: string\n                amount:\n                  type: number\n              required: [orderId, amount]\n      responses:\n        \"200\":\n          description: OK"
  }'
```

### Step 3: Register Your AI Agent as an OAuth2 Client (FR2)
```bash
curl -X POST http://localhost:8080/api/v1/agents \
  -u user:password \
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
  -u user:password \
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

# 3. Verify Hash-Chained Audit Trail Integrity (FR7, QA3)
curl -u user:password http://localhost:8080/api/v1/audit/verify
```

---

## Part 2: AI-Usage Log (Requirement C11)

- **IDE & Model Used:** Antigravity IDE (Gemini 4 Argon High)
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
