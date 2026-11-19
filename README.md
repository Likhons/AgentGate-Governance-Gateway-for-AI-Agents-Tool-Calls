# AgentGate: Governance Gateway for AI Agents' Tool Calls

CSE 444 Project 1. AgentGate is the single front door between AI agents and the tools they call (MCP servers and
plain REST endpoints). Every tool call is checked against policy, held for a human when risky, cleaned of sensitive
data, limited in volume and recorded in a tamper-evident log.

## Run It

The whole stack starts with one command on a laptop with 8 GB RAM (C1):

```bash
cp .env.example .env                    # replace every change_me value; no secrets live in the repository (C2)
docker compose up -d --build --wait     # PostgreSQL, Redis and the gateway
```

| What | Where |
| :--- | :--- |
| Dashboard (simulator, policy playground, registry, approvals, audit) | <http://localhost:8080/>; unlock it with the `AGENTGATE_ADMIN_*` values from `.env` |
| Approver console (FR4) | <http://localhost:8080/approvals/console> |
| API documentation (C3) | <http://localhost:8080/swagger-ui.html> and `/v3/api-docs` |
| Health and readiness (C4) | <http://localhost:8080/actuator/health> |
| Put a new REST tool behind the gateway in under 10 minutes (QA5) | [docs/QUICKSTART.md](docs/QUICKSTART.md), Part 1 |

Tests and quality-attribute checks (C5); CI runs both on every push ([.github/workflows/ci.yml](.github/workflows/ci.yml)):

```bash
./gradlew test                                            # unit, slice, ArchUnit/Modulith and Testcontainers tests (JDK 21, Docker)
docker compose --profile benchmark up -d --build --wait   # adds the mock downstream services
bash load-tests/run-live-qa.sh                            # QA2, QA1, QA4, QA3 and QA5 against the running stack
```

## How a Call Is Governed

`GatewayOrchestrationService` runs every call through the same steps, for REST calls and MCP calls alike:

1. **Identity:** the agent's OAuth2 token, with the end user bound to it, and the agent kill switch (FR2, FR8).
2. **Tool:** registry lookup, plus the tool and write-tool kill switches (FR1, FR8).
3. **Input guards:** ordered beans for schema validation, argument smuggling, rate limits, quotas and the write blast radius (FR5, FR6).
4. **Policy:** versioned rules with default deny and dry run, failing safe when the policy store is down (FR3, QA4).
5. **Approval:** high-risk calls pause for a human (FR4).
6. **Downstream:** each tool has a timeout and a circuit breaker (FR10).
7. **Output guards:** PII redaction by the agent's clearance, then truncation (FR5).
8. **Audit:** a hash-chained record is written for every outcome, including failures (FR7).

The code is a Spring Modulith modular monolith with eight modules: `shared`, `registry`, `identity`, `policy`,
`guard`, `approval`, `audit` and `gateway`. It runs on Spring MVC with Java 21 virtual threads. See
[ADR-001](docs/architecture/pattern-justification-and-adrs.md) and ADR-002 for the reasons.

## Requirement Map

Quoted test names are the start of a `@DisplayName` in
[AgentGateQualityAttributesIntegrationTest](src/test/java/com/cse444/agentgate/AgentGateQualityAttributesIntegrationTest.java)
(Testcontainers PostgreSQL 16 and Redis 7.4). "Evidence §n" is a section of [EVIDENCE_REPORT.md](docs/EVIDENCE_REPORT.md).

| ID | Requirement | Where | Evidence |
| :--- | :--- | :--- | :--- |
| FR1 | Tool registry, OpenAPI import, one MCP endpoint | `ToolRegistryService`, `OpenApiToolImporterService`, REST and MCP adapters, `GET /mcp/tools`, `McpToolListSynchronizer` | "FR1 Permitted Discovery", "FR1/FR8 Live MCP Tool List", live `qa5` |
| FR2 | Agents authenticate with OAuth2 client credentials; the end user is bound to the token | Spring Authorization Server (`AuthorizationServerConfig`, `AgentRegisteredClientRepository`, `AgentAccessTokenCustomizer`), `GatewayOrchestrationService#processAgentCall` | "FR2 Spring Authorization Server", "FR2/QA1 Delegated User Binding", ADR-005 |
| FR3 | Allow/deny by agent, tool and argument conditions, default deny, versioned, effective within 10 s, dry run | `PolicyEngineService`, `PolicyConditionEvaluator` | "FR2, FR3, FR9", `PolicyAndConditionUnitTest`, ADR-003 |
| FR4 | Human approval with web page and webhook, edit, timeout, clear result for the agent | `ApprovalWorkflowService`, sealed `ApprovalLifecycleState`, `ApprovalWebhookNotifier`, `/approvals/console`, `GET /api/v1/gateway/approvals/{id}` | "FR4 Approval Lifecycle", "FR4 Agent Result", "FR4 Clear Result", "FR4 Approver Webhooks" |
| FR5 | Schema validation, redaction by clearance (e-mail, BD phone, NID), truncation | `JsonSchemaValidationGuard`, `ArgumentSmugglingSecurityGuard`, `PiiRedactionOutputGuard`, `PayloadTruncationOutputGuard`, `GuardPipelineService` | `GuardAndStatePatternUnitTest`, "QA1 Security (Legitimate Traffic) & FR5 Output Redaction" |
| FR6 | Rate limits, daily quotas, write blast-radius cap, breach alert | `RateLimitAndBlastRadiusGuard` (Bucket4j and Redis), `LimitBreachAlertService`, `/api/v1/limits`, `/api/v1/limits/alerts` | "FR6 Rate Limits & Write Blast-Radius Cap", "FR6 Alerts", Evidence §9.3 |
| FR7 | Hash-chained audit trail, searchable and exportable | `AuditTrailService` (`@TransactionalEventListener`, HMAC-SHA256 chain), `/api/v1/audit`, `/verify`, `/export`, dashboard audit tab | "QA3 Auditability & FR7 CSV Export", ADR-004 |
| FR8 | Kill switch for an agent, a tool or all write tools; pending approvals cancelled | `/api/v1/agents/{id}/kill-switch`, `/api/v1/tools/{name}/kill-switch`, `/api/v1/tools/kill-switch/write-tools`, `KillSwitchActivatedEvent` | "QA5 Adoption & FR8 Kill Switches" |
| FR9 | Policy playground against current or draft policies | `POST /api/v1/policies/playground`, the dashboard's draft rules box | "FR2, FR3, FR9"; the dashboard's draft box was checked in a browser (AI-usage log Case 23) |
| FR10 | Per-tool timeouts and circuit breakers; tools that are down are hidden | `ResilientDownstreamInvoker`, `ResilienceConfig`, `GET /mcp/tools` | "FR1, FR10, C3, C4" |
| QA1 | 30 malicious calls 100% blocked or held; at most 5% of 200 legitimate calls blocked | [qa1_security_suite.k6.js](load-tests/qa1_security_suite.k6.js) | 30/30 blocked or held and 200/200 passed in the passing runs; one run that hit the daily quota is recorded in Evidence §7; CI |
| QA2 | At most 30 ms added at p95 at 100 calls/s | [qa2_gateway_latency.k6.js](load-tests/qa2_gateway_latency.k6.js) | Overhead p95 1–11 ms in the passing runs (Evidence §7–§11); two runs under host memory pressure failed and are recorded in §7; CI |
| QA3 | An auditor finds who, for whom, what and which rule in under a minute; tampering detected | Audit search by call ID, agent, user, tool, outcome and rule; `GET /api/v1/audit/verify` | "QA3 Auditability & FR7 CSV Export", live `qa3` |
| QA4 | Store down: MEDIUM/HIGH fail closed, LOW works for up to 5 minutes | ADR-003: policy snapshot, `FailSafeCaches`, policy-store probe on its own thread | Live `qa4` drill (`docker pause`), "QA4 Real Outage", "QA4 Fail-Closed Timing", Evidence §8 and §10 |
| QA5 | A classmate puts a new REST tool behind the gateway in under 10 minutes, no code changes | [QUICKSTART.md](docs/QUICKSTART.md) Part 1 | Live `qa5` (scripted path in 1 s, CI, Evidence §11); **classmate trial still to run** ([QA5_TRIAL.md](docs/QA5_TRIAL.md)) |
| C1 | Java 21, Spring Boot 4, Gradle, one-command Compose stack in 8 GB, no paid services | [docker-compose.yml](docker-compose.yml), [build.gradle](build.gradle) | About 0.7 GB for the whole stack after load (Evidence §7.8) |
| C2 | Profiles, environment variables, validated typed properties, no secrets in the repository | `dev`, `docker` and `test` profiles, `AgentGateProperties`, [.env.example](.env.example) | Startup fails when a secret is missing or too short |
| C3 | OpenAPI, Spring Security everywhere, Bean Validation, RFC 9457 errors, pagination on every list | springdoc, `SecurityConfig`, `AgentGateAuthorizationManager`, `@PreAuthorize`, `GlobalProblemDetailsHandler` | `WebSecurityAndProblemDetailsSliceTest`, "C3 Pagination" |
| C4 | Health and readiness, Micrometer metrics and tracing, JSON logs with a trace ID | Actuator (with a `policyStore` component), `agentgate.gateway.overhead`, [logback-spring.xml](src/main/resources/logback-spring.xml) | "FR1, FR10, C3, C4" |
| C5 | Unit, slice and Testcontainers tests; every quality attribute as a script; CI runs them all | [src/test](src/test/java/com/cse444/agentgate), [load-tests](load-tests) | 40 tests and live QA1–QA5 in CI |
| C6 | Architecture rules as tests | [ArchitectureRulesTest](src/test/java/com/cse444/agentgate/ArchitectureRulesTest.java) (Spring Modulith `verify()` and ArchUnit) | 6/6 in the full suite |
| C7 | C4 diagrams, at least 4 ADRs with measured evidence, utility tree with sensitivity and trade-off points | [c4-diagrams.md](docs/architecture/c4-diagrams.md), [ADR-001 to ADR-005 and the utility tree](docs/architecture/pattern-justification-and-adrs.md) | Six scenarios (S1–S6) |
| C8 | Pattern justification table | [pattern-justification-and-adrs.md](docs/architecture/pattern-justification-and-adrs.md) §1 | |
| C9 | Evidence report with raw outputs, charts and failed runs | [EVIDENCE_REPORT.md](docs/EVIDENCE_REPORT.md) | Includes failed runs and fixes (§7, §10, §11) |
| C10 | Checkpoint change request | **Waiting for the instructor's request**; it will be built as new components, with a note of the files touched in core code | |
| C11 | Incremental git history and an AI-usage log with wrong or incomplete agent output | `git log`; [QUICKSTART.md](docs/QUICKSTART.md) Part 2 | AI-usage log Cases 1–29 |
| C12 | Live defence | [DEFENCE_SCRIPT.md](docs/DEFENCE_SCRIPT.md) (walkthrough of ADR-003, change drills, questions) | |

## Documents

- [docs/QUICKSTART.md](docs/QUICKSTART.md): onboarding a new tool (QA5) and the AI-usage log (C11)
- [docs/architecture/c4-diagrams.md](docs/architecture/c4-diagrams.md): C4 context, container and component diagrams (C7)
- [docs/architecture/pattern-justification-and-adrs.md](docs/architecture/pattern-justification-and-adrs.md): pattern table (C8), ADR-001 to ADR-005 and the utility tree (C7)
- [docs/EVIDENCE_REPORT.md](docs/EVIDENCE_REPORT.md): results for every quality attribute, with raw outputs, charts and failed runs (C9)
- [docs/QA5_TRIAL.md](docs/QA5_TRIAL.md): the classmate trial for QA5
- [docs/DEFENCE_SCRIPT.md](docs/DEFENCE_SCRIPT.md): preparation for the live defence (C12)
- [project_status.md](project_status.md): what was built, step by step

## Stack

Java 21 with virtual threads and Spring Boot 4.0. Spring Security 7 runs Spring Authorization Server and the
resource server. The project also uses Spring AI MCP (server and client), Spring Modulith and ArchUnit, Resilience4j,
Micrometer, Bucket4j with Redis 7.4, Caffeine, PostgreSQL 16 with Flyway, Testcontainers, k6 and Docker Compose.
