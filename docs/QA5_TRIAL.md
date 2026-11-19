# QA5 Adoption Trial: Put a New REST Tool Behind AgentGate

> **QA5:** "A developer who has not seen the code (for example, a classmate) puts a new REST tool behind the
> gateway (import, risk level, policy) in under 10 minutes with no code changes."

`bash load-tests/run-live-qa.sh qa5` runs the same steps with curl on every CI run and shows that the documented path
works (`EVIDENCE_REPORT.md` §11). This trial measures the part a script cannot: a person who has never seen the code,
following only the quick start. Part A is for the host, Part B is the card you hand to the developer, and Part C is
where you record the result.

---

## Part A: For the Host (Before the Trial)

1. Start the stack, including the mock downstream that serves the "new" Invoice API:
   ```bash
   docker compose --profile benchmark up -d --build --wait
   curl -s http://localhost:8081/invoices/INV-2026-001     # an invoice with a customer e-mail
   ```
2. Open `http://localhost:8080/` and check that it shows **System UP**.
3. Write the admin username and password from your `.env` on a piece of paper for the developer. Never put them in
   the repository, a chat or a screenshot.
4. Give the developer only: Part B of this file, `docs/QUICKSTART.md` Part 1, the paper with the admin credentials,
   and a terminal with `curl` (Git Bash). Swagger UI at `http://localhost:8080/swagger-ui.html` is allowed too.
5. Do not show the source code. Help only when the developer is stuck, and write down every hint you give.
6. Start the timer when the developer starts reading Part B. Stop it when step 3 returns `"outcome":"EXECUTED"`.
7. Ask the developer to keep the terminal output; you will paste it into the evidence report.

---

## Part B: Task Card for the Developer

**Situation.** The billing team runs an Invoice API with one operation, `GET /invoices/{invoiceId}`. The gateway
reaches it at `http://mock-downstream:8081`. The customer-support bot (`support-bot`, secret `support-secret-2026`)
should be able to look up invoices, but only through AgentGate. Use `docs/QUICKSTART.md` Part 1 as your guide.
Do not change any code or configuration file.

First, load the admin credentials the host gave you into your shell:
```bash
ADMIN="<admin-username>:<admin-password>"
```

**Step 1: Import the Invoice API as a LOW-risk tool owned by BillingTeam.** Replace `XX` with your initials, so the
tool name is new (for example `get_invoice_rk`):
```bash
curl -X POST http://localhost:8080/api/v1/tools/import-openapi -u "$ADMIN" -H "Content-Type: application/json" -d '{
  "owningTeam": "BillingTeam",
  "defaultRiskLevel": "LOW",
  "baseUrlOverride": "http://mock-downstream:8081",
  "defaultTimeoutMs": 2000,
  "approvalWebhookUrl": null,
  "openApiContent": "openapi: 3.0.3\ninfo:\n  title: Invoice API\n  version: 1.0.0\npaths:\n  /invoices/{invoiceId}:\n    get:\n      operationId: get_invoice_XX\n      summary: Look up one invoice\n      parameters:\n        - name: invoiceId\n          in: path\n          required: true\n          schema:\n            type: string\n      responses:\n        \"200\":\n          description: OK"
}'
```
Expected: `201` with your tool, `"riskLevel":"LOW"`.

**Step 2: Write a policy.** Allow `support-bot` to call your tool, but only for invoice ids that look like
`INV-...`. Use the policy example in the quick start (Step 4) and change the name, the tool, the description and the
condition to: field `invoiceId`, operator `REGEX`, expected value `^INV-[0-9-]+$`.

**Step 3: Call your tool as the agent.** Get a token for end user `cust-200` (quick start Step 5), then call
`POST /api/v1/gateway/call` with:
```json
{"toolName": "get_invoice_XX", "arguments": {"invoiceId": "INV-2026-001"}}
```
Done when the reply says `"outcome":"EXECUTED"`. The customer's e-mail in the result should read `[REDACTED_EMAIL]`.

**Optional check:** `GET /mcp/tools` with the agent's token lists your tool.

---

## Part C: Result Form (Filled In by the Host)

| Item | Result |
| :--- | :--- |
| Date | |
| Developer (initials) | |
| Had they seen the AgentGate code before? (must be **No**) | |
| Tools used (curl, Swagger UI) | |
| Start time (began reading Part B) | |
| Step 1 done (import returned `201`) | |
| Step 2 done (policy returned `201`) | |
| Step 3 done (`"outcome":"EXECUTED"`) | |
| **Total time** (QA5 limit: **10 minutes**) | |
| Code or configuration files changed (must be **none**) | |
| Hints given by the host (what and when) | |
| Where the developer got stuck, or what the quick start did not explain | |
| E-mail redacted in the result? | |
| **QA5 result** (pass when under 10 minutes with no code changes) | |

**Afterwards:**
1. Add the table and the developer's terminal output to `docs/EVIDENCE_REPORT.md` as a new section ("QA5 Classmate
   Trial"). Record a failed or slow trial too, with what was changed in response (C9).
2. If the developer got stuck on something the quick start does not explain, fix `docs/QUICKSTART.md`, log it in the
   AI-usage log, and if possible run the trial again with another classmate.
