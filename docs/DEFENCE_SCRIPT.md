# AgentGate — C12 Live Defence Script (১৫ মিনিট)

> **C12 (course document):** "Live defence. 15 minutes: walk through one ADR and make a small
> instructor-requested change on the spot."
>
> এই script-এর সব command আর drill code 2026-11-19 তারিখে এই laptop-এ `main` (`92b2296`)-এর ওপর চালিয়ে
> যাচাই করা হয়েছে। তার পরে FR3 fix (`be75a8b`, Case 21) যোগ হয়েছে, আর তাতে drill-এর code বদলায় না।
> Line number-গুলো `be75a8b` অনুযায়ী। যেখানে যাচাই হয়নি, সেখানে **(যাচাই হয়নি)** লেখা আছে। "বলবেন:" অংশগুলো ইংরেজিতে
> রাখা হয়েছে, মুখস্থ করার দরকার নেই। মূল কথাটা নিজের ভাষায় বলবেন।

---

## 0. সময়ের ভাগ

| সময় | অংশ | কী দেখাবেন |
| :--- | :--- | :--- |
| 0:00–1:00 | Opening | Problem আর pipeline, এক নিঃশ্বাসে |
| 1:00–7:00 | ADR-003 walkthrough | Context → options → decision → **revision (Case 15)** → live QA4 drill (12 s) |
| 7:00–13:00 | On-the-spot change | sir-এর request → নতুন bean → test (≈45 s) → `git status`, দরকার হলে live call |
| 13:00–15:00 | Buffer / প্রশ্ন | §6-এর উত্তরগুলো |

ADR-003 বেছে নেওয়ার কারণ: এখানে প্রথম design-টা একটা আসল outage drill-এ ব্যর্থ হয়েছিল। তারপর মেপে মেপে
সেটা ঠিক করা হয়েছে, আর তার live প্রমাণ ১২ সেকেন্ডে দেখানো যায়। C7 ("measured evidence") আর C9 ("failed
runs and what was changed") দুটো দাবিই এক গল্পে পূরণ হয়। তবে sir অন্য ADR বেছে নিলে §4-এর cheat card দেখবেন।

---

## 1. Defence-এর ৩০ মিনিট আগে (pre-flight)

- [ ] অপ্রয়োজনীয় app বন্ধ করুন (Chrome-এর বাড়তি tab, Teams)। লক্ষ্য হলো **≥ 1.5 GB RAM খালি** রাখা।
      PowerShell-এ দেখার উপায়:
      `(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB`
- [ ] Docker Desktop চালু করুন। তারপর **Git Bash**-এ:

  ```bash
  cd /e/Assignment
  git status                                        # clean থাকতে হবে
  docker compose --profile benchmark up -d --wait   # mock-downstream ছাড়া lookup_order timeout হয় (409)
  set -a; . ./.env; set +a
  ADMIN="$AGENTGATE_ADMIN_USERNAME:$AGENTGATE_ADMIN_PASSWORD"

  # demo helper: tok <user> দিলে সেই end user-এর agent token আসে, call <token> '<json>' দিলে gateway call হয়
  tok()  { curl -s -X POST http://localhost:8080/oauth2/token \
             -d "grant_type=client_credentials&client_id=support-bot&client_secret=support-secret-2026&delegated_user=$1" \
             | sed -E 's/.*"access_token":"([^"]+)".*/\1/'; }
  call() { curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/api/v1/gateway/call \
             -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2"; }
  T=$(tok cust-100)
  call "$T" '{"toolName":"lookup_order","arguments":{"customerId":"cust-100"}}'   # HTTP 200 আসা চাই
  ```

- [ ] **Projector-এ কখনো `.env`, `echo $ADMIN` বা `cat .env` দেখাবেন না** (C2: repository-তে বা screen-এ কোনো secret নয়)।
- [ ] Gradle-কে একবার আগে থেকে চালিয়ে রাখুন, যাতে defence-এর সময় compile দ্রুত হয় (PowerShell, ≈45 s):

  ```powershell
  docker stop agentgate-app
  $env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.3\jbr"
  .\gradlew.bat --no-daemon --offline "-Porg.gradle.java.installations.paths=$env:JAVA_HOME" test --tests "com.cse444.agentgate.ArchitectureRulesTest" --tests "com.cse444.agentgate.GuardAndStatePatternUnitTest"
  docker start agentgate-app
  ```

- [ ] VS Code-এ এই tab-গুলো খুলে রাখুন, ক্রম অনুযায়ী:
  1. [pattern-justification-and-adrs.md](architecture/pattern-justification-and-adrs.md) (ADR-003 অংশ)
  2. [PolicyEngineService.java](../src/main/java/com/cse444/agentgate/policy/service/PolicyEngineService.java)
  3. [FailSafeCaches.java](../src/main/java/com/cse444/agentgate/shared/util/FailSafeCaches.java)
  4. [application.yml](../src/main/resources/application.yml)
  5. [InputCallGuard.java](../src/main/java/com/cse444/agentgate/guard/chain/InputCallGuard.java) ও [GuardPipelineService.java](../src/main/java/com/cse444/agentgate/guard/service/GuardPipelineService.java)
  6. [EVIDENCE_REPORT.md](EVIDENCE_REPORT.md) (§8 QA4, §9)
- [ ] Browser tab: `http://localhost:8080/` (dashboard), `/approvals/console`, `/swagger-ui.html`, আর GitHub Actions-এর
      সর্বশেষ green run (`44383901233`, দুটো job-ই success)।

---

## 2. Opening (১ মিনিট)

**বলবেন:**
> "AgentGate is the single front door between AI agents and their tools. Every call goes through one
> pipeline in `GatewayOrchestrationService`: OAuth2 agent identity and kill switch, ordered input guards,
> the policy engine with default deny, human approval for high-risk tools, the downstream call behind a
> Resilience4j circuit breaker, output redaction, and finally a hash-chained audit record, even when the
> call fails. It is a Spring Modulith monolith with eight modules on Spring MVC with virtual threads.
> I'll walk through ADR-003, because our first version of that decision failed a real outage test and
> we had to revise it with measurements."

---

## 3. ADR-003 walkthrough (৬ মিনিট)

**ADR-003: In-Memory Atomic Policy Snapshot with Risk-Tiered Fail-Safe TTL (FR3, QA2, QA4)**

### 3.1 Context (১ মিনিট)

**বলবেন:**
> "Three requirements pull against each other. FR3: a policy change must apply within 10 seconds without
> a restart. QA2: the gateway may add at most 30 ms at p95 at 100 calls per second. QA4: if the policy
> store is unreachable, medium- and high-risk tools must fail closed, while low-risk tools keep working on
> the last known policies for up to 5 minutes."

### 3.2 Options (৩০ সেকেন্ড)

1. প্রতিটা call-এ PostgreSQL query করা: সবসময় সর্বশেষ policy পাওয়া যায়, কিন্তু hot path-এ DB round-trip থাকে। আর DB down হলে সব call আটকে যায়, তাই QA4 পূরণ সম্ভব না।
2. **Atomic in-memory snapshot**, প্রতি 10 s-এ refresh হয়, সাথে risk অনুযায়ী আলাদা fail-safe আচরণ। ← এটাই বেছে নেওয়া হয়েছে

### 3.3 Decision: code-এ কোথায় (২ মিনিট, tab ঘুরিয়ে দেখান)

| কী | কোথায় |
| :--- | :--- |
| Immutable snapshot একটা `AtomicReference`-এ, lock নেই | [PolicyEngineService.java:62-64](../src/main/java/com/cse444/agentgate/policy/service/PolicyEngineService.java#L62-L64) |
| Startup-এ আর প্রতি 10 s-এ reload (`@PostConstruct` + `@Scheduled`) | [PolicyEngineService.java:103-118](../src/main/java/com/cse444/agentgate/policy/service/PolicyEngineService.java#L103-L118) |
| API দিয়ে policy বদলালে সাথে সাথে refresh | [PolicyEngineService.java:201](../src/main/java/com/cse444/agentgate/policy/service/PolicyEngineService.java#L201) |
| Policy store probe: প্রতি 2 s, virtual thread-এ, 1.5 s পার হলে outage ধরা হয় | [PolicyEngineService.java:125-151](../src/main/java/com/cse444/agentgate/policy/service/PolicyEngineService.java#L125-L151) |
| Risk-tiered fail-safe: MEDIUM/HIGH deny, LOW চলবে snapshot-এর বয়স 5 মিনিট পর্যন্ত | [PolicyEngineService.java:223-262](../src/main/java/com/cse444/agentgate/policy/service/PolicyEngineService.java#L223-L262) |
| Agent, tool আর limit-এর last-known-good cache (Caffeine, refresh 10 s, expire 5 m) | [FailSafeCaches.java:29-41](../src/main/java/com/cse444/agentgate/shared/util/FailSafeCaches.java#L29-L41) |
| Hikari 2 s connection timeout, 30 s socket timeout, probe-এর config | [application.yml:34-42](../src/main/resources/application.yml#L34-L42), [application.yml:95-101](../src/main/resources/application.yml#L95-L101) |

**বলবেন:**
> "Evaluation reads one immutable snapshot, so there is no database query and no lock on the hot path.
> A separate probe checks PostgreSQL every two seconds with a 1.5-second bound, so a hung connection also
> counts as an outage. When the store is down, medium and high risk are denied with
> `QA4_FAIL_CLOSED_HIGH_OR_MEDIUM_RISK`; low risk is evaluated on the cached snapshot until it is older
> than five minutes, then it is denied too."

### 3.4 Revision: AI-usage log Case 15 (১ মিনিট, এখানেই সবচেয়ে বেশি নম্বর)

**বলবেন:**
> "Our first version only cached the policies, and the flag-based test passed. But when we really paused
> the PostgreSQL container, low-risk calls failed after about ten seconds: agent, tool and limit lookups
> still read the database, `@Transactional` needed a connection even on a cache hit, and Hikari's default
> 30-second timeout made calls hang first. So we extended the decision to the whole hot path: last-known-good
> caches with the same 5-minute bound, non-transactional cached reads, a 2-second Hikari timeout, the
> 2-second probe, and an in-order audit retry queue so outage-time calls are still hash-chained afterwards."

এটা ADR-এর *Revision (2026-11-19)* অংশে লেখা আছে। পুরো ঘটনা [QUICKSTART.md](QUICKSTART.md)-এর AI-usage log-এ (Case 15)।

### 3.5 Live প্রমাণ (১ মিনিট)

```bash
bash load-tests/run-live-qa.sh qa4
```

2026-11-19-এর আসল output (মোট ১২ সেকেন্ড লেগেছিল):

```
== qa4: PostgreSQL outage drill (docker pause agentgate-postgres)
policyStore health during the outage: DOWN
PASS  qa4: LOW-risk call during the outage -> 200 EXECUTED in 25 ms
PASS  qa4: MEDIUM-risk call during the outage -> 409 fail closed
PASS  qa4: HIGH-risk call during the outage -> 409 fail closed
PASS  qa4: the outage-time call was hash-chained after recovery (policyStore UP)
== 0 failed check(s); results in build/live-qa/
```

**বলবেন:**
> "This really pauses the database container. The low-risk lookup still succeeds, refund and payout fail
> closed, and after the database returns, the outage-time call appears in the audit chain. The same drill
> runs in CI as the `live-quality-attributes` job, and as a Testcontainers test,
> `qa4RealDatabaseOutageKeepsLowRiskToolsWorkingAndFailsClosed`."

Script-টা নিজেই unpause করে, আর কোনো কারণে মাঝপথে থেমে গেলেও `trap` দিয়ে unpause হয়। তবু কিছু গোলমাল মনে হলে চালান: `docker unpause agentgate-postgres`

### 3.6 Trade-off আর sensitivity point (৩০ সেকেন্ড)

- **Trade-off:** MEDIUM/HIGH-এর জন্য consistency (CP), আর LOW-এর জন্য availability (AP), তবে staleness সর্বোচ্চ ৫ মিনিট। এর বেশি নয়।
- **Sensitivity:** probe interval আর timeout (2 s / 1.5 s)। ছোট করলে GC pause বা বেশি load-এর সময় ভুয়া outage ধরা পড়বে, ফলে MEDIUM call অকারণে deny হবে। বড় করলে outage ধরতে দেরি হবে।
- **Trade-off:** এই instance-এর বাইরে থেকে agent, tool বা limit বদলালে তা কার্যকর হতে 10 s পর্যন্ত লাগতে পারে। তবে এই instance-এর kill switch সাথে সাথে কাজ করে।

### 3.7 ADR-003 নিয়ে সম্ভাব্য প্রশ্ন

| প্রশ্ন | উত্তর |
| :--- | :--- |
| Outage-এর শুরুর কয়েক সেকেন্ডে MEDIUM call-এর কী হয়? | Probe ধরার আগ পর্যন্ত, অর্থাৎ সর্বোচ্চ ≈ 2 s interval + 1.5 s timeout, MEDIUM call cached state-এ evaluate হতে পারে। এটা জানা ও সীমাবদ্ধ একটা window। ছোট করতে চাইলে probe interval কমাতে হবে (sensitivity point)। |
| 5:01 মিনিটে কী হয়? | Snapshot-এর বয়স TTL পার হলে LOW call `QA4_FAIL_CLOSED_CACHE_TTL_EXPIRED` দিয়ে deny হয়। Cache-গুলোও `expireAfterWrite(5m)`-এ মেয়াদ হারায়, তাই সবকিছু fail closed হয়ে যায়। |
| Policy cache Redis-এ রাখলেন না কেন? | তাহলে hot path-এ আরেকটা network hop যোগ হতো। তার ওপর Redis নিজেও down হতে পারে। QA4-এর প্রশ্নটাই হলো store down থাকলে কী হবে, remote cache রাখলে সমস্যাটা শুধু জায়গা বদলায়। |
| PostgreSQL `LISTEN/NOTIFY` দিয়ে instant reload করলেন না কেন? | FR3 10 s সময় দেয়, আর API দিয়ে করা write-এ এমনিতেই সাথে সাথে refresh হয়। NOTIFY-এর জন্য আলাদা একটা স্থায়ী connection লাগত, আর outage-এর সময় সেটা কোনো কাজেও আসে না। |
| `simulatedOutage` flag আলাদা কেন? | Drill আর test-এর জন্য। Probe চলতে থাকলেও যেন drill-কে বাতিল করে দিতে না পারে ([PolicyEngineService.java:65-66](../src/main/java/com/cse444/agentgate/policy/service/PolicyEngineService.java#L65-L66))। |
| Multi-instance হলে? | প্রতিটা instance-এর নিজস্ব snapshot থাকে। অন্য instance-এর বদল 10 s-এর মধ্যে আসে। ADR-এ trade-off হিসেবে লেখা আছে। |

---

## 4. sir অন্য ADR চাইলে: cheat card

প্রতিটা card-এর গঠন একই: decision → দুটো কারণ → মাপা সংখ্যা → demo → দুর্বল দিক।

**ADR-001: Spring MVC + Virtual Threads, WebFlux নয়**
- কারণ: JPA, Flyway আর `@TransactionalEventListener` সবই blocking stack। Virtual thread-এ blocking I/O carrier thread ছেড়ে দেয়, তাই reactive code না লিখেই concurrency পাওয়া যায়, আর stack trace সরল থাকে।
- সংখ্যা: QA2-তে 100 RPS-এ gateway overhead p95 প্রথমে 5 ms, সর্বশেষ run-এ 2 ms (limit 30 ms)। [EVIDENCE_REPORT.md](EVIDENCE_REPORT.md)।
- Demo: [application.yml:19-21](../src/main/resources/application.yml#L19-L21) (`spring.threads.virtual.enabled: true`)।
- দুর্বল দিক: `synchronized` block-এর ভেতরে blocking I/O করলে thread pinning হয়। প্রথম audit writer-এ ঠিক এই সমস্যা ছিল। পরে সেটা `ReentrantLock` আর একটা single-writer queue দিয়ে বদলানো হয়।

**ADR-002: Spring Modulith modular monolith, microservices নয়**
- কারণ: governance stage-গুলোর মাঝে কোনো network hop নেই (QA2), আর পুরো stack 8 GB-এর মধ্যে চলে (C1)।
- সংখ্যা: পুরো stack post-load অবস্থায় 735.2 MiB। `ArchitectureRulesTest` 6/6 পাস, কোনো cycle নেই।
- Demo: §1-এর Gradle command (`ArchitectureRulesTest`, test অংশ ≈ 8 s)।
- Rule ভাঙলে কী হয় দেখাতে চাইলে: `gateway`-এর কোনো class-এ `audit` module-এর কোনো class-কে field হিসেবে *ব্যবহার* করুন। শুধু import লিখলে হবে না, কারণ bytecode-এ সেটা থাকে না। তাহলে `auditModuleMustBeEventDrivenAndNeverCalledDirectly` fail করবে। **(যাচাই হয়নি, rehearsal-এ একবার চালিয়ে দেখুন)**
- দুর্বল দিক: সব module একসাথে scale হয়, আলাদা করে নয়।

**ADR-004: HMAC-SHA256 hash-chained audit, `@TransactionalEventListener(AFTER_COMPLETION)` + `REQUIRES_NEW`**
- কারণ: rollback হওয়া (block হওয়া) call-ও audit-এ থেকে যায়। HMAC-এর key DB-তে নয়, environment-এ থাকে। তাই কারও কাছে DB credential থাকলেও সে নতুন hash হিসাব করতে পারবে না।
- সংখ্যা: live `/api/v1/audit/verify`, 2026-11-19-এ 36,740টা record intact।
- QA3 UI demo (৩০ সেকেন্ড): dashboard-এ "Execute Governed Call", তারপর "Find this call in the audit trail"। ঠিক ওই call-টার record খোলে, যাতে থাকে agent, কোন user-এর পক্ষে, redacted arguments আর কোন rule সিদ্ধান্ত দিয়েছে। সাথে Audit tab-এ user, tool, outcome বা rule দিয়ে search করা যায়।
- Demo: `curl -s -u "$ADMIN" http://localhost:8080/api/v1/audit/verify`। Tamper দেখাতে হলে **Testcontainers test** চালান:
  `--tests "com.cse444.agentgate.AgentGateQualityAttributesIntegrationTest.qa3AuditTrailDetectsTampering"`
  (Compile আর container start সহ মোট ≈ 71 s, test নিজে 2 s)। শেষে Hikari বা `eventPublicationRegistry`-এর কিছু WARN log আসে। এগুলো container বন্ধ হওয়ার সময়কার স্বাভাবিক log, `BUILD SUCCESSFUL` দেখলেই হবে।
- **⚠️ Live DB-তে কখনো `UPDATE audit_log_entries` চালাবেন না।** Graded evidence, অর্থাৎ পুরো chain-টা, নষ্ট হয়ে যাবে।
- দুর্বল দিক: §6-এর "শেষের row মুছে ফেললে?" প্রশ্নটা দেখুন।

**ADR-005: Spring Authorization Server, আর end user token-এ বাঁধা**
- কারণ: hand-written token endpoint বাদ দিয়ে standard client-credentials, introspection, revocation আর metadata। আর `delegated_user` token-এ বাঁধা থাকে, তাই prompt-injected agent call-এর মাঝখানে customer বদলাতে পারে না (QA1)।
- Demo (2026-11-19-এ live যাচাই করা):

  ```bash
  T=$(tok cust-100)
  call "$T" '{"toolName":"lookup_order","arguments":{"customerId":"cust-100"}}'                            # HTTP 200
  call "$T" '{"toolName":"lookup_order","delegatedUserId":"cust-999","arguments":{"customerId":"cust-999"}}' # HTTP 403 Delegated User Mismatch
  call "$T" '{"toolName":"lookup_order","arguments":{"customerId":"cust-999"}}'                            # HTTP 409 DEFAULT_DENY
  ```

- দুর্বল দিক: JWT self-contained, তাই revoke করলেও মেয়াদ (1 h) শেষ না হওয়া পর্যন্ত token চলে। তবে agent kill switch সাথে সাথে block করে। HS256 কেন? Issuer আর validator একই process-এ, তাই JWK বিতরণের দরকার নেই। বাইরের resource server যোগ হলে RS256 নিতে হবে।

---

## 5. On-the-spot change (৬ মিনিট)

### 5.1 প্রতিবার একই ৬টা ধাপ

1. **Request আবার বলুন আর requirement-এর সাথে মেলান** (৩০ s)। *"So you want X. That is FR5, so it goes in the guard chain as a new bean; I won't touch `GatewayOrchestrationService`."*
2. **Branch খুলুন:** `git switch -c defence/<short-name>`
3. **Code লিখুন** (২–৩ মিনিট): §5.3–5.6-এর drill থেকে template নিন।
4. **Test চালান** (≈ 45 s, PowerShell):

   ```powershell
   docker stop agentgate-app
   $env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.3\jbr"
   .\gradlew.bat --no-daemon --offline "-Porg.gradle.java.installations.paths=$env:JAVA_HOME" test --tests "com.cse444.agentgate.DefenceDrillTest" --tests "com.cse444.agentgate.ArchitectureRulesTest"
   docker start agentgate-app
   ```

   `ArchitectureRulesTest` সাথে রাখার কারণ: এতে প্রমাণ হয় নতুন code module-এর সীমা ভাঙেনি (C6)।
5. **কোন file নতুন আর কোনটা core, দেখান:** `git status --short`। নতুন file `??` দেখায়, আর বদলানো core file `M`। এটা C10-এর "files touched in core code versus new components"-এর ভাষাতেই বলুন।
6. **Live (সময় থাকলে, ≈ 70–84 s):** `docker compose up -d --build --wait agentgate`, তারপর একটা `call` করুন, তারপর audit search।

### 5.2 কোন request কোথায় যাবে

| sir-এর request (উদাহরণ) | কোথায় | Core edit? | Drill |
| :--- | :--- | :--- | :--- |
| "Tool চালানোর আগে X block করো" (দৈর্ঘ্য, keyword, সময়, নির্দিষ্ট customer) | নতুন `InputCallGuard` bean, `guard/chain`-এ | **না** | D1 |
| "Result থেকে Y লুকাও" (card, bKash নম্বর, passport) | নতুন `OutputResultGuard` bean | **না** (তবে audit-এ রাখা argument-ও redact করতে হলে `PiiSanitizer` বদলাতে হবে) | D2 |
| "Policy-তে নতুন operator" (`NOT_IN`, `STARTS_WITH`, `BETWEEN`) | `PolicyCondition.Operator` + evaluator-এর `switch` | হ্যাঁ, policy module-এ দুটো ছোট edit। Switch exhaustive, তাই কিছু বাদ গেলে compiler নিজেই ধরিয়ে দেয় | D3 |
| "Refund-এর সীমা 2000 থেকে 1000 করো" | API: আগে playground-এ draft, তারপর `POST /api/v1/policies` | **code লাগবে না** | D4 |
| "Approval timeout, truncation size বা blast radius-এর default বদলাও" | [application.yml](../src/main/resources/application.yml), তারপর rebuild | **code লাগবে না** | D5 |
| "Policy deny-তে 409 না দিয়ে 403 দাও" | নতুন exception + [GlobalProblemDetailsHandler.java](../src/main/java/com/cse444/agentgate/shared/error/GlobalProblemDetailsHandler.java) + gateway-তে throw বদলানো | হ্যাঁ, core | §5.7 |

### 5.3 Drill D1: নতুন input guard (যাচাই করা: unit test, ArchUnit আর live 422 + audit)

উদাহরণ request: *"1000 character-এর বেশি লম্বা argument block করো।"*

নতুন file `src/main/java/com/cse444/agentgate/guard/chain/ArgumentLengthGuard.java`:

```java
package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.shared.error.GuardRejectedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Input Guard (@Order(15)): rejects any argument value longer than the configured limit (FR5).
 * Runs after schema validation (10) and before anti-smuggling (20) and rate limits (30),
 * so a rejected call does not use up the agent's rate-limit tokens.
 */
@Component
@Order(15)
public class ArgumentLengthGuard implements InputCallGuard {

    private final int maxChars;

    public ArgumentLengthGuard(@Value("${agentgate.guards.max-argument-chars:1000}") int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public String guardName() {
        return "ArgumentLengthGuard";
    }

    @Override
    public void verifyInput(GuardInspectionContext context) {
        Map<String, Object> arguments = context.arguments();
        if (arguments == null) {
            return;
        }
        arguments.forEach((field, value) -> {
            if (value != null && String.valueOf(value).length() > maxChars) {
                throw new GuardRejectedException(guardName(),
                        "Argument '" + field + "' is longer than " + maxChars + " characters (FR5)");
            }
        });
    }
}
```

নতুন file `src/test/java/com/cse444/agentgate/DefenceDrillTest.java`:

```java
package com.cse444.agentgate;

import com.cse444.agentgate.guard.chain.ArgumentLengthGuard;
import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.shared.error.GuardRejectedException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefenceDrillTest {

    @Test
    void argumentLengthGuardBlocksOverlongValues() {
        ArgumentLengthGuard guard = new ArgumentLengthGuard(10);

        assertThatCode(() -> guard.verifyInput(
                new GuardInspectionContext(null, null, Map.of("orderId", "ORD-1")))
        ).doesNotThrowAnyException();

        assertThatThrownBy(() -> guard.verifyInput(
                new GuardInspectionContext(null, null, Map.of("note", "x".repeat(11))))
        ).isInstanceOf(GuardRejectedException.class).hasMessageContaining("note");
    }
}
```

Live call (rebuild করার পরে):

```bash
LONG="cust-100$(printf 'x%.0s' $(seq 1 1001))"
call "$T" "{\"toolName\":\"lookup_order\",\"arguments\":{\"customerId\":\"$LONG\"}}"
# -> HTTP 422, "title":"Blocked by Input Guard (ArgumentLengthGuard)"
curl -s -u "$ADMIN" "http://localhost:8080/api/v1/audit?policyRuleName=ArgumentLengthGuard&size=1"
# -> "outcome":"REJECTED_BY_GUARD", "policyRuleName":"ArgumentLengthGuard", agent আর user সহ
```

**বলবেন (এটাই আসল পয়েন্ট):**
> "I added one class and touched no existing file. Spring injects `List<InputCallGuard>` sorted by
> `@Order`, so `GuardPipelineService` picks it up. Because the orchestrator already maps
> `GuardRejectedException` to an audit record, a metric and an RFC 9457 422 response, the new guard
> got all three for free. That is the Chain of Responsibility plus Open/Closed argument from our pattern table."

`@Order(15)` কেন: schema guard-এর (10) পরে চলে, তাই আগে type ঠিক আছে কিনা দেখা হয়ে যায়। আর rate limit-এর (30) আগে চলে, তাই reject হওয়া call agent-এর quota খরচ করে না।

### 5.4 Drill D2: নতুন output redaction (যাচাই করা: unit test)

উদাহরণ request: *"Tool result-এ card number থাকলে লুকাও।"* নতুন file `guard/chain/CardNumberRedactionOutputGuard.java`:

```java
package com.cse444.agentgate.guard.chain;

import com.cse444.agentgate.guard.dto.GuardInspectionContext;
import com.cse444.agentgate.shared.domain.ClearanceLevel;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Output Guard (@Order(15)): redacts 16-digit card numbers in tool results unless the agent has
 * FULL_PII clearance (FR5). Runs after PII redaction (10) and before truncation (20).
 */
@Component
@Order(15)
public class CardNumberRedactionOutputGuard implements OutputResultGuard {

    private static final Pattern CARD_NUMBER =
            Pattern.compile("(?<!\\d)\\d{4}([ -]?)\\d{4}\\1\\d{4}\\1\\d{4}(?!\\d)");

    @Override
    public String guardName() {
        return "CardNumberRedactionOutputGuard";
    }

    @Override
    public String transformOutput(GuardInspectionContext context, String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }
        if (context.identity() != null && context.identity().clearanceLevel() == ClearanceLevel.FULL_PII) {
            return rawOutput;
        }
        return CARD_NUMBER.matcher(rawOutput).replaceAll("[REDACTED_CARD]");
    }
}
```

Test method (`DefenceDrillTest`-এ যোগ করুন; import লাগবে `CardNumberRedactionOutputGuard`, `AgentCallIdentity`, `ClearanceLevel`, `assertThat`):

```java
@Test
void cardNumbersAreRedactedUnlessFullPii() {
    CardNumberRedactionOutputGuard guard = new CardNumberRedactionOutputGuard();
    String raw = "{\"card\":\"4111 1111 1111 1111\",\"card2\":\"5500-0000-0000-0004\",\"orderId\":\"ORD-12\"}";

    GuardInspectionContext none = new GuardInspectionContext(
            new AgentCallIdentity("support-bot", "cust-1", ClearanceLevel.NONE), null, null);
    GuardInspectionContext full = new GuardInspectionContext(
            new AgentCallIdentity("audit-bot", "cust-1", ClearanceLevel.FULL_PII), null, null);

    assertThat(guard.transformOutput(none, raw))
            .doesNotContain("4111 1111 1111 1111")
            .doesNotContain("5500-0000-0000-0004")
            .contains("[REDACTED_CARD]")
            .contains("ORD-12");
    assertThat(guard.transformOutput(full, raw)).isEqualTo(raw);
}
```

সৎভাবে বলবেন: এই guard শুধু result redact করে। Audit-এ রাখা argument redact হয় `PiiSanitizer` দিয়ে ([GuardPipelineService.java:76-83](../src/main/java/com/cse444/agentgate/guard/service/GuardPipelineService.java#L76-L83))। সেটাতেও card লুকাতে হলে shared core-এর `PiiSanitizer`-এ একটা pattern যোগ করতে হবে।

### 5.5 Drill D3: নতুন policy operator `NOT_IN` (যাচাই করা: unit test)

[PolicyCondition.java](../src/main/java/com/cse444/agentgate/policy/domain/PolicyCondition.java)-এর enum-এ `IN`-এর পরে যোগ করুন:

```java
        IN,
        NOT_IN,
```

[PolicyConditionEvaluator.java](../src/main/java/com/cse444/agentgate/policy/service/PolicyConditionEvaluator.java)-এর `switch`-এ, `IN`-এর case-এর পরে:

```java
            case NOT_IN -> Arrays.stream(expectedStr.split(","))
                    .map(String::trim)
                    .noneMatch(actualStr::equals);
```

Test method (import লাগবে `PolicyCondition`, `PolicyCondition.Operator`, `PolicyConditionEvaluator`, `List`, `assertThat`):

```java
@Test
void notInOperatorRejectsListedValues() {
    PolicyConditionEvaluator evaluator = new PolicyConditionEvaluator();
    List<PolicyCondition> blockUsd = List.of(new PolicyCondition("currency", Operator.NOT_IN, "USD,EUR"));

    assertThat(evaluator.matchesAll(blockUsd, Map.of("currency", "BDT"), null)).isTrue();
    assertThat(evaluator.matchesAll(blockUsd, Map.of("currency", "USD"), null)).isFalse();
}
```

**⚠️ পুরোনো operator-এর নাম কখনো বদলাবেন না বা মুছবেন না** (যেমন `LTE`-কে `LESS_OR_EQUAL` করা)। শুধু নতুন যোগ করুন। নাম বদলালে DB-তে থাকা rule আর parse হবে না। Case 21-এর fix-এর পর সেই rule fail closed হয়, ফলে যেমন সব refund call deny হয়ে যাবে আর QA1 suite ভেঙে যাবে।

### 5.6 Drill D4: code ছাড়া policy বদলানো (playground অংশ live যাচাই করা)

আগে **playground** দেখান, কারণ এতে live state একটুও বদলায় না (FR9):

```bash
curl -s -u "$ADMIN" -X POST http://localhost:8080/api/v1/policies/playground -H 'Content-Type: application/json' \
  -d '{"agentClientId":"support-bot","delegatedUserId":"cust-100","toolName":"issue_refund","arguments":{"orderId":"ORD-1","amount":1500},
       "draftRules":[{"ruleName":"allow-refund-up-to-2000-bdt","priority":20,"agentPattern":"support-bot","toolPattern":"issue_refund",
       "effect":"ALLOW","dryRun":false,"conditions":[{"field":"amount","operator":"LTE","expectedValue":"1000"}],"description":"draft cap 1000"}]}'
# draft ছাড়া পাঠালে: "allowed":true, rule allow-refund-up-to-2000-bdt v5
# এই draft দিয়ে পাঠালে: "allowed":false, "matchedRuleName":"DEFAULT_DENY"
```

sir সত্যিই live-এ বদলাতে বললে একই `ruleName` দিয়ে `POST /api/v1/policies` পাঠান (body একই, শুধু `draftRules` ছাড়া)। এতে নতুন version (v6) তৈরি হয় আর সাথে সাথে কার্যকর হয় (FR3)। **শেষে অবশ্যই `"expectedValue":"2000"` দিয়ে আবার POST করে ফিরিয়ে আনুন।** QA1 suite 2000 BDT-এর সীমা ধরে চলে। আর version history থেকেই দেখানো যায় যে policy versioned।

Limit বদলাতে বললে (FR6): `PUT /api/v1/limits`। **এরপর ফিরিয়ে আনতে হবে।** QA1-এর বর্তমান মান হলো `issue_refund` → `requestsPerMinute 1000, dailyQuota 10000, writeBlastRadiusPerHour 1000`।

### 5.7 Drill D5: config default বদলানো, আর বড় request এলে

- Default বদলাতে [application.yml](../src/main/resources/application.yml)-এ মান বদলান, তারপর `docker compose up -d --build --wait agentgate` (≈ 70–84 s)। উদাহরণ: `approval.default-timeout: 15m`, `guards.max-response-bytes: 65536`, `limits.default-write-blast-radius-per-hour: 20`। Typed `@ConfigurationProperties`-এ validation আছে (যেমন `@Min(256)`), তাই ভুল মান দিলে app startup-এই থেমে যাবে। এটা C2-এর পক্ষে একটা পয়েন্ট। **(rebuild-এর সময় মাপা হয়েছে। নির্দিষ্টভাবে এই মান বদলানো যাচাই হয়নি)**
- **"403 for policy deny"** বা অন্য কোনো core change চাইলে: আগে বলে নিন যে এটা core edit। তারপর সবচেয়ে ছোট অংশটা করুন: একটা `PolicyDeniedException` (shared/error), তার handler (403), আর gateway-এ `DENIED_BY_POLICY`-এর জায়গায় সেটা throw করা ([GatewayOrchestrationService.java:138-141](../src/main/java/com/cse444/agentgate/gateway/service/GatewayOrchestrationService.java#L138-L141))। মনে রাখবেন, `run-live-qa.sh` এখন QA4-এ `409 fail closed` আশা করে, তাই এটা বদলালে script-ও বদলাতে হবে। **(যাচাই হয়নি)**
- সময়ে না কুলালে বলুন: *"Here is where it goes, here is the test I would write; the smallest slice is this."* এরপর অন্তত test-টা লিখে ফেলুন।

### 5.8 Defence-এর পরে পরিষ্কার করা

```bash
git switch main
git branch -D defence/<short-name>         # রাখতে চাইলে মুছবেন না, পরে commit করা যাবে
docker compose up -d --build --wait agentgate   # live container আবার main-এর code-এ ফেরে (≈ 70 s)
```

---

## 6. কঠিন প্রশ্ন আর সৎ উত্তর

| প্রশ্ন | উত্তর |
| :--- | :--- |
| Policy deny-তে 403 না দিয়ে 409 কেন? | Gateway deny-কে `IllegalStateException` হিসেবে ছুড়ে দেয়, যেটা "Operation Blocked by Governance State" (409)-এ map হয়। Body-তে কারণ আর rule-এর নাম থাকে। এটা জানা একটা উন্নতির জায়গা: আলাদা exception দিলে 403 পাওয়া যাবে (§5.7)। Downstream timeout-ও এখন 409 দেয়, অথচ সেখানে 504 বেশি মানানসই। |
| Strategy pattern বলছেন, কিন্তু এটা তো `switch`? | এটা enum দিয়ে dispatch করা strategy। Switch exhaustive, তাই নতুন operator যোগ করলে compiler নিজেই case চায়। পুরোপুরি OCP চাইলে `Map<Operator, ConditionStrategy>` bean লাগবে। কিন্তু 9টা ছোট operator-এর জন্য ততটা indirection বাড়ানো লাভজনক মনে হয়নি। এটাই trade-off। |
| Rate limit policy-র আগে কেন? | Flood থামাতে। Prompt-injected agent বারবার deny হওয়ার মতো call পাঠালেও throttle হয়, আর FR6 alert ওঠে। এর মূল্য হলো deny হওয়া call-ও quota খরচ করে। |
| DB-তে কোনো policy-র `conditions_json` নষ্ট হলে কী হবে? | **Fail closed** ([PolicyEngineService.java:462-491](../src/main/java/com/cse444/agentgate/policy/service/PolicyEngineService.java#L462-L491))। যে call ওই rule পর্যন্ত পৌঁছায় তা deny হয়, কারণে লেখা থাকে "has unreadable conditions"। সাথে একটা ERROR log ওঠে, আর tool-টা `/mcp/tools`-এ দেখায় না। Dry-run DENY rule তখনও block করে না। আর API এখন অসম্পূর্ণ শর্ত সরাসরি 400 দিয়ে ফেরত দেয় (Case 22), তাই এমন rule এখন শুধু সরাসরি DB edit-এর মাধ্যমেই তৈরি হতে পারে। আগে এটা fail-open ছিল: খালি list ফেরত আসত, ফলে 2,000 BDT-এর সীমা উঠে যেত। Defence-এর প্রস্তুতির সময় এটা ধরা পড়ে, আর ঠিক করার আগে test-টা পুরোনো code-এ fail করে প্রমাণ করেছে (AI-usage log Case 21)। এটা একটা ভালো C11 গল্প। |
| কেউ audit-এর **শেষ** row মুছে দিলে? | বাকি chain তখনও ঠিক দেখায়। মাঝখান থেকে মুছলে বা কিছু বদলালে ধরা পড়ে, কিন্তু শেষ অংশ ছেঁটে ফেললে hash chain একা সেটা ধরতে পারে না। সমাধান: chain-এর মাথার hash সময়ে সময়ে বাইরে কোথাও রাখা (log, WORM storage)। |
| QA2-তে শুধু gateway-র সময় মাপছেন, downstream বাদ দিয়ে, কীভাবে নিশ্চিত হলেন? | `agentgate.gateway.overhead` metric মোট সময় থেকে downstream-এর nanos বাদ দিয়ে হিসাব করে ([GatewayOrchestrationService.java:218-228](../src/main/java/com/cse444/agentgate/gateway/service/GatewayOrchestrationService.java#L218-L228))। k6-এ HTTP p95 আলাদাভাবে রিপোর্ট করা হয়। |
| Spring Statemachine না নিয়ে sealed interface কেন? | ছয়টা state (`PENDING` থেকে পাঁচটা terminal: `APPROVED`, `APPROVED_EDITED`, `REJECTED`, `TIMED_OUT`, `CANCELLED_BY_KILL_SWITCH`) আর একটাই উৎস, তাই sealed `ApprovalLifecycleState`-এর compile-time check-ই যথেষ্ট। কোনো বাড়তি dependency বা config লাগে না। Kill switch, timeout আর মানুষের approve একসাথে ঘটলেও terminal state আর বদলায় না (`GuardAndStatePatternUnitTest`)। |
| AI কতটা লিখেছে, আর আপনি কীভাবে যাচাই করেছেন? | C11 log-এ Cases 1–22 আছে, তার মধ্যে AI-এর অনেকগুলো ভুল output (যেমন Case 15, 21 আর 22)। প্রতিটা fix আলাদা commit-এ, full suite (38/38) আর CI পাস করে তবেই। |

---

## 7. কিছু ভেঙে গেলে (fallback)

- Docker বা app ভেঙে গেলে: CI-এর green run দেখান (`live-quality-attributes` job-এ QA1–QA4 live চলে), [EVIDENCE_REPORT.md](EVIDENCE_REPORT.md)-এর §8/§9-এর raw output দেখান, আর শুধু unit test চালিয়ে change প্রমাণ করুন। Unit test-এর জন্য app লাগে না।
- `lookup_order` থেকে 409 timeout আসছে? তাহলে `mock-downstream` চলছে না: `docker compose --profile benchmark up -d --wait`
- RAM শেষ হয়ে আসছে? Gradle চালানোর আগে `docker stop agentgate-app`, পরে `docker start agentgate-app`।
- QA4 drill মাঝপথে থেমে গেছে? `docker unpause agentgate-postgres`

---

## 8. Rehearsal plan

1. Timer চালিয়ে পুরো ১৫ মিনিট একবার: Opening, তারপর ADR-003 (§3), তারপর D1 শুরু থেকে শেষ (§5.3, live call সহ)।
2. Branch মুছে আরেকবার: এবার D3 (§5.5), কিন্তু snippet না দেখে লিখবেন।
3. কাউকে §6 থেকে এলোমেলোভাবে ৩টা প্রশ্ন জিজ্ঞেস করতে বলুন। প্রতিটা উত্তর ৩০ সেকেন্ডের মধ্যে দিতে হবে।
4. শেষে §5.8 দিয়ে `main`-এ ফিরুন, আর দেখে নিন `git status` clean কিনা।
