# AgentGate — C4 Architecture Diagrams (Context, Container, Component)

## 1. C4 Level 1 — System Context Diagram

```mermaid
flowchart TB
    subgraph Actors["External Actors & Clients"]
        Agent["AI Agent / MCP Client\n(Support Bot, Billing Bot)"]
        Admin["Security Administrator\n(Registers Tools, Policies, Kill Switches)"]
        Approver["Human Approver\n(Reviews High-Risk Calls via Web Console / Webhook)"]
        Auditor["Compliance Auditor\n(Searches, Exports & Verifies Hash-Chained Audit Trail)"]
    end

    AgentGate["AgentGate Governance Gateway\n(Spring Boot 4.0 / Java 21 Virtual Threads)\nSingle MCP & REST Front Door"]

    subgraph Downstream["Downstream Business Systems"]
        RestServices["Internal REST Microservices\n(Order API, Refund API, Payout API)"]
        McpServers["Downstream MCP Tool Servers\n(JSON-RPC 2.0 Tool Providers)"]
        WebhookSink["Enterprise Slack / Webhook Receiver\n(Approval & Limit Breach Alerts)"]
    end

    Agent -->|"1. OAuth2 Client Credentials + MCP/REST Tool Calls"| AgentGate
    Admin -->|"2. OpenAPI Import, Versioned Policies, Kill Switch"| AgentGate
    Approver -->|"3. Approve / Edit Args / Reject High-Risk Calls"| AgentGate
    Auditor -->|"4. Query, Export CSV & Run Verify Command"| AgentGate

    AgentGate -->|"5. Circuit-Broken & Timed-Out HTTP Calls"| RestServices
    AgentGate -->|"6. Circuit-Broken JSON-RPC tools/call"| McpServers
    AgentGate -->|"7. Async Pending Approval Webhooks"| WebhookSink
```

---

## 2. C4 Level 2 — Container Diagram (< 8 GB RAM Docker Compose Stack)

```mermaid
flowchart LR
    Agent["AI Agent\n(OAuth2 Client)"]
    Approver["Human Approver\n(Browser / Webhook)"]

    subgraph DockerHost["Docker Compose Host (Total Limit: 1.75 GB / 8 GB RAM Budget)"]
        App["agentgate-app\nSpring Boot 4.0 + Java 21 (ZGC)\nSpring MVC Virtual Threads + Spring AI MCP\n[Mem Limit: 1024 MB]"]
        PG[("agentgate-postgres\nPostgreSQL 16 Alpine\nTools, Agents, Policies, Approvals, Hash-Chained Audit\n[Mem Limit: 512 MB]")]
        Redis[("agentgate-redis\nRedis 7.4 Alpine\nBucket4j Rate Limits, Daily Quotas, Write Blast-Radius\n[Mem Limit: 256 MB]")]
    end

    Downstream["Downstream REST & MCP Tool Servers"]

    Agent -->|"HTTPS / OAuth2 Bearer JWT\nPOST /api/v1/gateway/call & GET /mcp/tools"| App
    Approver -->|"GET /approvals/console\nPOST /api/v1/approvals/{id}/approve"| App
    App -->|"Spring Data JPA / HikariCP / Flyway"| PG
    App -->|"Spring Data Redis / Bucket4j"| Redis
    App -->|"Resilience4j CircuitBreaker + TimeLimiter"| Downstream
```

---

## 3. C4 Level 3 — Component / Spring Modulith Diagram

```mermaid
flowchart TB
    subgraph AgentGate["com.cse444.agentgate (Spring Modulith Bounded Contexts)"]
        Gateway["gateway\nGatewayController, McpUnifiedEndpointController\nGatewayOrchestrationService\n(Facade / Command Orchestrator)\nMcpToolListSynchronizer, McpToolCallbackBridge"]
        Identity["identity (FR2, FR8)\nAgentIdentityController\nAgentIdentityService\nOAuth2 Client Credentials & Agent Kill Switch"]
        Registry["registry (FR1, FR8, FR10)\nToolRegistryService, OpenApiToolImporterService\nResilientDownstreamInvoker\nRestDownstreamToolAdapter\nMcpDownstreamToolAdapter (MCP SDK client)"]
        Policy["policy (FR3, FR9, QA4)\nPolicyEngineService (10s Hot-Reload + 5m Fail-Safe)\nPolicyConditionEvaluator\nAgentGateAuthorizationManager"]
        Guard["guard (FR5, FR6, QA1)\nGuardPipelineService\nJsonSchemaValidationGuard (@Order 10)\nArgumentSmugglingSecurityGuard (@Order 20)\nRateLimitAndBlastRadiusGuard (@Order 30)\nPiiRedactionOutputGuard (@Order 10)\nPayloadTruncationOutputGuard (@Order 20)"]
        Approval["approval (FR4, FR8)\nApprovalWorkflowService\nApprovalLifecycleState (State Pattern)\nApprovalWebhookNotifier"]
        Shared["shared (OPEN Kernel)\nToolCallAuditRecordedEvent\nKillSwitchActivatedEvent\nLimitBreachAlertEvent\nAgentGateProperties"]
        Audit["audit (FR7, QA3)\nAuditTrailService (@TransactionalEventListener)\nAuditHashChainCalculator (HMAC-SHA256)\nAuditTrailController"]
    end

    Gateway --> Identity
    Gateway --> Registry
    Gateway --> Guard
    Gateway --> Policy
    Gateway --> Approval
    Gateway -->|"Publishes ToolCallAuditRecordedEvent"| Shared
    Identity -->|"Publishes KillSwitchActivatedEvent"| Shared
    Registry -->|"Publishes KillSwitchActivatedEvent"| Shared
    Guard -->|"Publishes LimitBreachAlertEvent"| Shared
    Shared -.->|"@EventListener cancels PENDING"| Approval
    Shared -.->|"@TransactionalEventListener appends hash chain"| Audit
```
