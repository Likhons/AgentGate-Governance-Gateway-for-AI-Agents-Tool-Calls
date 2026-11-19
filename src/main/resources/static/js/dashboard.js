const AgentGateUI = (() => {
    const STORAGE_KEY = 'agentgate_operator_auth';

    const PRESETS = {
        order: {
            tool: 'lookup_order',
            user: 'cust-100',
            args: { customerId: 'cust-100' }
        },
        refund_ok: {
            tool: 'issue_refund',
            user: 'cust-100',
            args: { orderId: 'ORD-101', amount: 1200 }
        },
        refund_over: {
            tool: 'issue_refund',
            user: 'cust-100',
            args: { orderId: 'ORD-999', amount: 50000 }
        },
        tenant: {
            tool: 'lookup_order',
            user: 'cust-100',
            args: { customerId: 'cust-victim-999' }
        },
        smuggle: {
            tool: 'lookup_order',
            user: 'cust-100',
            args: { customerId: "cust-100'; DROP TABLE audit_log_entries; -- ignore previous rules" }
        },
        payout: {
            tool: 'bulk_payout',
            user: 'cust-100',
            args: { recipient: 'vendor-dhaka-01', amount: 15000 }
        }
    };

    // FR9: a draft that lowers the refund cap, so the same 1,200 BDT refund is allowed by the
    // active policies but denied by the draft
    const SAMPLE_DRAFT = [{
        ruleName: 'allow-refund-up-to-1000-bdt',
        priority: 20,
        agentPattern: 'support-bot',
        toolPattern: 'issue_refund',
        effect: 'ALLOW',
        dryRun: false,
        conditions: [{ field: 'amount', operator: 'LTE', expectedValue: '1000' }],
        description: 'Draft: lower the refund cap to 1,000 BDT'
    }];

    // QA3/FR7: audit search fields, keyed by the /api/v1/audit query parameter they fill
    const AUDIT_FILTERS = {
        callId: 'audit-callid-filter',
        agentClientId: 'audit-agent-filter',
        delegatedUserId: 'audit-user-filter',
        toolName: 'audit-tool-filter',
        outcome: 'audit-outcome-filter',
        policyRuleName: 'audit-rule-filter'
    };
    const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const AUDIT_PAGE_SIZE = 20;
    let auditPageIndex = 0;
    let lastCallId = null;

    function getAuthHeader() {
        return sessionStorage.getItem(STORAGE_KEY);
    }

    function showToast(msg) {
        const el = document.getElementById('toast');
        el.textContent = msg;
        el.style.display = 'block';
        clearTimeout(el._timer);
        el._timer = setTimeout(() => { el.style.display = 'none'; }, 3200);
    }

    function esc(v) {
        return String(v ?? '')
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#39;');
    }

    async function apiFetch(url, options = {}) {
        const auth = getAuthHeader();
        const headers = new Headers(options.headers || {});
        if (auth && !headers.has('Authorization')) {
            headers.set('Authorization', auth);
        }
        const res = await fetch(url, { ...options, headers });
        const text = await res.text();
        let data = null;
        try {
            data = text ? JSON.parse(text) : null;
        } catch {
            data = text;
        }
        return { ok: res.ok, status: res.status, data, raw: text };
    }

    async function checkHealth() {
        const dot = document.getElementById('health-dot');
        const txt = document.getElementById('health-text');
        try {
            const res = await apiFetch('/actuator/health');
            if (res.ok && res.data && res.data.status === 'UP') {
                dot.className = 'pulse-dot up';
                txt.textContent = 'System UP';
            } else {
                dot.className = 'pulse-dot down';
                txt.textContent = `Status ${res.status}`;
            }
        } catch {
            dot.className = 'pulse-dot down';
            txt.textContent = 'Unreachable';
        }
    }

    function switchTab(tab) {
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
        document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.id === `panel-${tab}`));
    }

    function applyPreset(key) {
        const p = PRESETS[key];
        if (!p) return;
        document.getElementById('sim-tool').value = p.tool;
        document.getElementById('sim-user').value = p.user;
        document.getElementById('sim-args').value = JSON.stringify(p.args, null, 2);
    }

    function setRail(states, summaryText) {
        const ids = ['identity', 'policy', 'guard', 'downstream', 'audit'];
        ids.forEach(id => {
            const node = document.getElementById(`stage-${id}`);
            const detail = document.getElementById(`stage-${id}-detail`);
            const cfg = states[id] || { state: 'idle', text: 'Idle' };
            node.dataset.state = cfg.state;
            if (cfg.text) detail.textContent = cfg.text;
        });
        document.getElementById('rail-summary').textContent = summaryText;
    }

    async function executeGatewayCall() {
        const agent = document.getElementById('sim-agent').value.trim();
        const secret = document.getElementById('sim-secret').value;
        const user = document.getElementById('sim-user').value.trim();
        const tool = document.getElementById('sim-tool').value.trim();

        let argsObj;
        try {
            argsObj = JSON.parse(document.getElementById('sim-args').value);
        } catch (err) {
            showToast('Invalid JSON in Arguments box');
            return;
        }

        setRail({
            identity: { state: 'active', text: 'Minting OAuth2 JWT...' },
            policy: { state: 'idle', text: 'Waiting' },
            guard: { state: 'idle', text: 'Waiting' },
            downstream: { state: 'idle', text: 'Waiting' },
            audit: { state: 'idle', text: 'Waiting' }
        }, `Executing ${tool} as ${agent}...`);

        const formBody = new URLSearchParams({
            grant_type: 'client_credentials',
            client_id: agent,
            client_secret: secret,
            delegated_user: user
        });

        const tokenRes = await fetch('/oauth2/token', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: formBody.toString()
        });
        const tokenJson = await tokenRes.json().catch(() => ({}));
        if (!tokenRes.ok || !tokenJson.access_token) {
            setRail({
                identity: { state: 'fail', text: `OAuth2 ${tokenRes.status} Rejected` }
            }, `Identity rejected (${tokenRes.status})`);
            renderSimResult(tokenRes.status, 'AUTH_FAILED', '—', '—', tokenJson, false);
            return;
        }

        const callRes = await fetch('/api/v1/gateway/call', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${tokenJson.access_token}`,
                'X-On-Behalf-Of-User': user
            },
            body: JSON.stringify({ toolName: tool, delegatedUserId: user, arguments: argsObj })
        });
        const body = await callRes.json().catch(() => ({}));

        if (callRes.ok && body.outcome === 'EXECUTED') {
            setRail({
                identity: { state: 'pass', text: `${agent} (${user})` },
                policy: { state: 'pass', text: `${body.policyRuleName}@v${body.policyRuleVersion}` },
                guard: { state: 'pass', text: 'Schema, Security, Quota OK' },
                downstream: { state: 'pass', text: 'Executed & PII Redacted' },
                audit: { state: 'pass', text: `Trace ${String(body.traceId || '').slice(0, 8)} appended` }
            }, `EXECUTED in ${body.gatewayLatencyMs} ms gateway overhead`);
            renderSimResult(callRes.status, body.outcome, `${body.policyRuleName}@v${body.policyRuleVersion}`, `${body.gatewayLatencyMs} ms`, body, false);
        } else if (callRes.status === 202 || body.outcome === 'HELD_FOR_APPROVAL') {
            setRail({
                identity: { state: 'pass', text: `${agent} (${user})` },
                policy: { state: 'pass', text: `${body.policyRuleName}@v${body.policyRuleVersion}` },
                guard: { state: 'pass', text: 'Pre-guards cleared' },
                downstream: { state: 'warn', text: `Paused HITL (${String(body.approvalId || '').slice(0, 8)})` },
                audit: { state: 'pass', text: 'Held call audited' }
            }, `HELD_FOR_APPROVAL — review in tab 03 Human Approvals`);
            renderSimResult(callRes.status, body.outcome, `${body.policyRuleName}@v${body.policyRuleVersion}`, `${body.gatewayLatencyMs} ms`, body, true);
        } else {
            const title = body.title || `HTTP ${callRes.status}`;
            const isGuard = title.toLowerCase().includes('guard') || title.toLowerCase().includes('validation') || callRes.status === 422 || callRes.status === 429;
            setRail({
                identity: { state: callRes.status === 401 ? 'fail' : 'pass', text: callRes.status === 401 ? 'Token Failed' : `${agent} (${user})` },
                policy: { state: !isGuard && callRes.status === 403 ? 'fail' : 'pass', text: !isGuard && callRes.status === 403 ? title : 'Rule checked' },
                guard: { state: isGuard ? 'fail' : 'idle', text: isGuard ? title : 'Stopped before guard' },
                downstream: { state: 'idle', text: 'Never invoked (safe)' },
                audit: { state: 'pass', text: 'Blocked call recorded' }
            }, `BLOCKED (${callRes.status}): ${title}`);
            renderSimResult(callRes.status, title, 'DENIED', '—', body, false);
        }

        if (getAuthHeader()) {
            refreshOperatorData();
        }
    }

    async function testPlayground() {
        if (!getAuthHeader()) {
            showToast('Unlock Operator Console first to use Policy Playground');
            return;
        }
        let argsObj;
        try {
            argsObj = JSON.parse(document.getElementById('sim-args').value);
        } catch {
            showToast('Invalid JSON in Arguments box');
            return;
        }
        const agent = document.getElementById('sim-agent').value.trim();
        const user = document.getElementById('sim-user').value.trim();
        const tool = document.getElementById('sim-tool').value.trim();
        const draftRules = readDraftRules();
        if (draftRules === undefined) return;

        const payload = { agentClientId: agent, delegatedUserId: user, toolName: tool, arguments: argsObj };
        if (draftRules) payload.draftRules = draftRules;
        const mode = draftRules
            ? `${draftRules.length} draft rule${draftRules.length === 1 ? '' : 's'}`
            : 'active policies';

        const res = await apiFetch('/api/v1/policies/playground', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const d = res.data || {};
        if (!res.ok) {
            const detail = d.detail || d.title || `HTTP ${res.status}`;
            setRail({
                identity: { state: 'pass', text: `${agent} (${user})` },
                policy: { state: 'fail', text: 'Playground request rejected' },
                guard: { state: 'idle', text: 'Dry Playground Mode' },
                downstream: { state: 'idle', text: 'Not executed (FR9)' },
                audit: { state: 'idle', text: 'Zero side-effects' }
            }, `Playground rejected (${res.status}): ${detail}`);
            renderSimResult(res.status, 'PLAYGROUND: REJECTED', '—', '—', d, false);
            showToast(`Playground ${res.status}: ${detail}`);
            return;
        }
        const allow = Boolean(d.allowed);
        setRail({
            identity: { state: 'pass', text: `${agent} (${user})` },
            policy: { state: allow ? 'pass' : 'fail', text: `${d.matchedRuleName || 'DEFAULT_DENY'}@v${d.matchedRuleVersion || 0}` },
            guard: { state: 'idle', text: 'Dry Playground Mode' },
            downstream: { state: 'idle', text: 'Not executed (FR9)' },
            audit: { state: 'idle', text: 'Zero side-effects' }
        }, `Playground (${mode}): ${d.effectiveDecision || res.status} — ${d.reason || ''}`);
        renderSimResult(res.status, `PLAYGROUND (${draftRules ? 'DRAFT' : 'ACTIVE'}): ${d.effectiveDecision}`, `${d.matchedRuleName || 'DEFAULT'}@v${d.matchedRuleVersion || 0}`, '0 ms (dry)', d, false);
    }

    /**
     * FR9: reads the optional draft rules box. Returns null to test the active policies,
     * the draft rules to test them instead, or undefined when the box is invalid.
     */
    function readDraftRules() {
        const text = document.getElementById('sim-drafts').value.trim();
        if (!text) return null;
        let parsed;
        try {
            parsed = JSON.parse(text);
        } catch {
            showToast('Invalid JSON in Draft Rules box');
            return undefined;
        }
        const rules = Array.isArray(parsed) ? parsed : [parsed];
        if (rules.length === 0) return null;
        if (rules.some(r => !r || typeof r !== 'object' || typeof r.priority !== 'number')) {
            showToast('Each draft rule needs ruleName, priority (a number), agentPattern, toolPattern and effect');
            return undefined;
        }
        // dryRun is a required boolean on the server; a draft may leave it out
        return rules.map(r => ({ dryRun: false, ...r }));
    }

    function loadSampleDraft() {
        document.getElementById('sim-drafts').value = JSON.stringify(SAMPLE_DRAFT, null, 2);
        document.getElementById('sim-draft-box').open = true;
    }

    function clearDraft() {
        document.getElementById('sim-drafts').value = '';
    }

    function renderSimResult(status, outcome, rule, latency, payload, showApprovalJump) {
        // QA3: a governed call returns its callId, which is also its audit record's id
        lastCallId = payload && typeof payload.callId === 'string' ? payload.callId : null;
        document.getElementById('audit-jump').style.display = lastCallId ? 'block' : 'none';
        const badge = document.getElementById('sim-status-badge');
        badge.textContent = `HTTP ${status}`;
        badge.className = 'badge ' + (status === 200 ? 'badge-ok' : status === 202 ? 'badge-warn' : 'badge-err');
        document.getElementById('res-outcome').textContent = outcome || '—';
        document.getElementById('res-rule').textContent = rule || '—';
        document.getElementById('res-latency').textContent = latency || '—';
        document.getElementById('approval-jump').style.display = showApprovalJump ? 'block' : 'none';
        document.getElementById('sim-output').textContent = JSON.stringify(payload, null, 2);
    }

    async function unlockOperator(silent = false) {
        const u = document.getElementById('op-user').value.trim();
        const p = document.getElementById('op-pass').value;
        const header = 'Basic ' + btoa(`${u}:${p}`);
        const probe = await apiFetch('/api/v1/audit/verify', { headers: { Authorization: header } });
        if (!probe.ok && probe.status === 401) {
            if (!silent) showToast('Invalid credentials (use the AGENTGATE_ADMIN_* values from .env)');
            return;
        }
        sessionStorage.setItem(STORAGE_KEY, header);
        document.getElementById('unlock-btn').textContent = `Unlocked (${u})`;
        document.getElementById('unlock-btn').className = 'btn btn-ok btn-sm';
        ['registry', 'approvals', 'audit'].forEach(section => {
            document.getElementById(`${section}-lock-notice`).style.display = 'none';
            document.getElementById(`${section}-content`).style.display = '';
        });
        await refreshOperatorData();
        if (!silent) showToast(`Operator console unlocked as ${u}`);
    }

    async function refreshOperatorData() {
        if (!getAuthHeader()) return;
        await Promise.all([
            loadMetrics(),
            loadTools(),
            loadPolicies(),
            loadAgents(),
            loadApprovals(),
            loadAudit(),
            verifyAuditChain(true)
        ]);
    }

    async function loadMetrics() {
        const m = await apiFetch('/actuator/metrics/agentgate.gateway.overhead');
        if (m.ok && m.data && Array.isArray(m.data.measurements)) {
            const count = m.data.measurements.find(x => x.statistic === 'COUNT')?.value ?? 0;
            const total = m.data.measurements.find(x => x.statistic === 'TOTAL_TIME')?.value ?? 0;
            document.getElementById('kpi-calls').textContent = Math.round(count).toLocaleString();
            document.getElementById('kpi-overhead').textContent = count > 0 ? `${((total / count) * 1000).toFixed(2)} ms` : '0 ms';
        }
    }

    function riskBadge(level) {
        if (level === 'HIGH' || level === 'CRITICAL') return `<span class="badge badge-err">${esc(level)}</span>`;
        if (level === 'MEDIUM') return `<span class="badge badge-warn">${esc(level)}</span>`;
        return `<span class="badge badge-ok">${esc(level)}</span>`;
    }

    async function loadTools() {
        const r = await apiFetch('/api/v1/tools?page=0&size=50');
        if (!r.ok || !r.data) return;
        const items = r.data.content || [];
        document.getElementById('kpi-tools').textContent = items.length;
        const writeTools = items.filter(t => t.writeOperation);
        const allWriteDisabled = writeTools.length > 0 && writeTools.every(t => t.status === 'DISABLED');
        const wkBadge = document.getElementById('write-kill-badge');
        wkBadge.textContent = allWriteDisabled ? 'Write Tools: KILLED' : 'Write Tools: Active';
        wkBadge.className = 'badge ' + (allWriteDisabled ? 'badge-err' : 'badge-ok');

        const tb = document.getElementById('tools-tbody');
        tb.innerHTML = items.map(t => `
            <tr>
                <td><strong>${esc(t.name)}</strong><div style="font-size:11px;color:var(--ink-muted)">${esc(t.owningTeam)}</div></td>
                <td class="mono">${esc(t.toolType)} · ${esc(t.httpMethod || 'MCP')}</td>
                <td>${riskBadge(t.riskLevel)}</td>
                <td><span class="badge ${t.circuitBreakerState === 'CLOSED' ? 'badge-ok' : 'badge-err'}">${esc(t.circuitBreakerState)}</span></td>
                <td><span class="badge ${t.status === 'ENABLED' ? 'badge-ok' : 'badge-err'}">${esc(t.status)}</span></td>
                <td>${t.status === 'ENABLED'
                    ? `<button class="btn btn-danger btn-sm" onclick="AgentGateUI.toggleTool('${esc(t.name)}', false)">Kill</button>`
                    : `<button class="btn btn-ok btn-sm" onclick="AgentGateUI.toggleTool('${esc(t.name)}', true)">Enable</button>`}
                </td>
            </tr>
        `).join('') || '<tr><td colspan="6">No tools registered.</td></tr>';
    }

    async function toggleTool(name, enable) {
        const path = enable ? `/api/v1/tools/${encodeURIComponent(name)}/enable` : `/api/v1/tools/${encodeURIComponent(name)}/kill-switch`;
        const r = await apiFetch(path, { method: 'POST' });
        if (r.ok) {
            showToast(`${name} ${enable ? 'enabled' : 'killed'}`);
            loadTools();
        }
    }

    async function toggleGlobalWriteKill(disableAll) {
        const r = await apiFetch(`/api/v1/tools/kill-switch/write-tools?disableAllWrites=${disableAll}`, { method: 'POST' });
        if (r.ok) {
            showToast(disableAll ? 'ALL write tools killed' : 'Write tools restored');
            refreshOperatorData();
        }
    }

    async function loadPolicies() {
        const r = await apiFetch('/api/v1/policies?page=0&size=50');
        if (!r.ok || !r.data) return;
        const tb = document.getElementById('policies-tbody');
        tb.innerHTML = (r.data.content || []).map(p => `
            <tr>
                <td><strong>${esc(p.ruleName)}</strong> <span class="mono">v${p.version}</span>${p.dryRun ? ' <span class="badge badge-warn">DRY</span>' : ''}</td>
                <td class="mono">${esc(p.agentPattern)} → ${esc(p.toolPattern)}</td>
                <td><span class="badge ${p.effect === 'ALLOW' ? 'badge-ok' : 'badge-err'}">${esc(p.effect)}</span></td>
                <td class="mono">${p.priority}</td>
            </tr>
        `).join('') || '<tr><td colspan="4">No active policies.</td></tr>';
    }

    async function reloadPolicies() {
        const r = await apiFetch('/api/v1/policies/reload', { method: 'POST' });
        if (r.ok) {
            showToast('Policy snapshot reloaded');
            loadPolicies();
        }
    }

    async function loadAgents() {
        const r = await apiFetch('/api/v1/agents?page=0&size=50');
        if (!r.ok || !r.data) return;
        const tb = document.getElementById('agents-tbody');
        tb.innerHTML = (r.data.content || []).map(a => `
            <tr>
                <td><strong class="mono">${esc(a.clientId)}</strong><div style="font-size:11px;color:var(--ink-muted)">${esc(a.agentName)}</div></td>
                <td>${esc(a.owningTeam)}</td>
                <td><span class="badge">${esc(a.clearanceLevel)}</span></td>
                <td><span class="badge ${a.status === 'ACTIVE' ? 'badge-ok' : 'badge-err'}">${esc(a.status)}</span></td>
                <td>${a.status === 'ACTIVE'
                    ? `<button class="btn btn-danger btn-sm" onclick="AgentGateUI.toggleAgent('${esc(a.clientId)}', false)">Kill</button>`
                    : `<button class="btn btn-ok btn-sm" onclick="AgentGateUI.toggleAgent('${esc(a.clientId)}', true)">Enable</button>`}
                </td>
            </tr>
        `).join('') || '<tr><td colspan="5">No registered agents.</td></tr>';
    }

    async function toggleAgent(id, enable) {
        const path = enable ? `/api/v1/agents/${encodeURIComponent(id)}/enable` : `/api/v1/agents/${encodeURIComponent(id)}/kill-switch`;
        const r = await apiFetch(path, { method: 'POST' });
        if (r.ok) {
            showToast(`Agent ${id} ${enable ? 'enabled' : 'killed'}`);
            loadAgents();
        }
    }

    async function loadApprovals() {
        const filter = document.getElementById('approval-filter').value;
        const url = filter === 'PENDING' ? '/api/v1/approvals?state=PENDING&page=0&size=25' : '/api/v1/approvals?page=0&size=25';
        const r = await apiFetch(url);
        if (!r.ok || !r.data) return;
        const list = r.data.content || [];
        if (filter === 'PENDING') {
            document.getElementById('kpi-approvals').textContent = r.data.totalElements ?? list.length;
        }
        const tb = document.getElementById('approvals-tbody');
        tb.innerHTML = list.map(item => `
            <tr>
                <td><strong class="mono">${esc(item.agentClientId)}</strong><div style="font-size:11px;color:var(--ink-muted)">user: ${esc(item.delegatedUserId || '—')}</div></td>
                <td><strong>${esc(item.toolName)}</strong><div class="mono" style="font-size:11px;color:var(--ink-muted)">${esc(item.policyRuleName)}@v${item.policyRuleVersion}</div></td>
                <td style="min-width:240px;">
                    <textarea id="ap-args-${esc(item.id)}" class="mono" rows="2" ${item.state !== 'PENDING' ? 'readonly' : ''}>${esc(item.effectiveArgumentsJson || item.originalArgumentsJson)}</textarea>
                </td>
                <td><span class="badge ${item.state === 'PENDING' ? 'badge-warn' : item.state === 'APPROVED' || item.state === 'EXECUTED' ? 'badge-ok' : 'badge-err'}">${esc(item.state)}</span>
                    <div class="mono" style="font-size:10.5px;color:var(--ink-muted);margin-top:4px;">${esc(item.expiresAt)}</div>
                </td>
                <td>${item.state === 'PENDING' ? `
                    <div class="btn-row" style="margin-top:0;">
                        <button class="btn btn-ok btn-sm" onclick="AgentGateUI.decideApproval('${esc(item.id)}', 'approve')">Approve / Edit</button>
                        <button class="btn btn-danger btn-sm" onclick="AgentGateUI.decideApproval('${esc(item.id)}', 'reject')">Reject</button>
                    </div>` : `<span class="mono" style="font-size:11px;color:var(--ink-secondary)">${esc(item.decisionNote || item.approverId || 'Resolved')}</span>`}
                </td>
            </tr>
        `).join('') || '<tr><td colspan="5">No approval requests matching filter.</td></tr>';
    }

    async function decideApproval(id, action) {
        let body;
        if (action === 'approve') {
            try {
                const edited = JSON.parse(document.getElementById(`ap-args-${id}`).value);
                body = { note: 'Approved from Control Plane Dashboard', editedArguments: edited };
            } catch {
                showToast('Invalid JSON in Approval arguments');
                return;
            }
        } else {
            body = { note: 'Rejected from Control Plane Dashboard' };
        }
        const r = await apiFetch(`/api/v1/approvals/${id}/${action}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (r.ok) {
            showToast(`Approval ${action}d`);
            refreshOperatorData();
        } else {
            showToast(`Error ${r.status}: ${r.data?.detail || 'Request failed'}`);
        }
    }

    /** QA3/FR7: the filled-in search fields as query parameters (exact matches, combined with AND). */
    function auditFilterParams() {
        const params = new URLSearchParams();
        Object.entries(AUDIT_FILTERS).forEach(([param, id]) => {
            const value = document.getElementById(id).value.trim();
            if (value) params.set(param, value);
        });
        return params;
    }

    function searchAudit() {
        auditPageIndex = 0;
        return loadAudit();
    }

    function clearAuditSearch() {
        Object.values(AUDIT_FILTERS).forEach(id => { document.getElementById(id).value = ''; });
        return searchAudit();
    }

    function auditPage(delta) {
        auditPageIndex = Math.max(0, auditPageIndex + delta);
        return loadAudit();
    }

    async function loadAudit() {
        const params = auditFilterParams();
        const callId = params.get('callId');
        if (callId && !UUID_PATTERN.test(callId)) {
            showToast('Call ID must be the UUID returned by the gateway');
            return;
        }
        params.set('page', String(auditPageIndex));
        params.set('size', String(AUDIT_PAGE_SIZE));
        const r = await apiFetch(`/api/v1/audit?${params}`);
        if (!r.ok || !r.data) {
            showToast(`Audit search failed (${r.status}): ${r.data?.detail || r.data?.title || 'request rejected'}`);
            return;
        }
        const page = r.data;
        const total = page.totalElements ?? 0;
        const pages = page.totalPages ?? 0;
        const filtered = [...auditFilterParams().keys()].length > 0;
        document.getElementById('audit-count').textContent = filtered
            ? `${total} matching record${total === 1 ? '' : 's'} (exact-match filters). Click a row for details.`
            : `${total} record${total === 1 ? '' : 's'} in the audit trail. Click a row for details.`;
        document.getElementById('audit-page').textContent = pages ? `page ${(page.number ?? 0) + 1} / ${pages}` : '';
        document.getElementById('audit-newer').disabled = !pages || page.first !== false;
        document.getElementById('audit-older').disabled = !pages || page.last !== false;

        const rows = page.content || [];
        const tb = document.getElementById('audit-tbody');
        tb.innerHTML = rows.map(e => `
            <tr class="audit-row" onclick="AgentGateUI.toggleAuditDetail(${Number(e.sequenceNumber)})" title="Show redacted arguments, approval and trace">
                <td class="mono">#${e.sequenceNumber}</td>
                <td><strong class="mono">${esc(e.agentClientId)}</strong><div style="font-size:11px;color:var(--ink-muted)">${esc(e.delegatedUserId || '—')}</div></td>
                <td><strong>${esc(e.toolName)}</strong><div class="mono" style="font-size:11px;color:var(--ink-muted)">${esc(e.policyRuleName || '—')}@v${e.policyRuleVersion}</div></td>
                <td><span class="badge ${e.outcome === 'EXECUTED' || e.outcome === 'APPROVED_EXECUTED' ? 'badge-ok' : e.outcome === 'HELD_FOR_APPROVAL' ? 'badge-warn' : 'badge-err'}">${esc(e.outcome)}</span>
                    <span class="mono" style="margin-left:6px;font-size:11px;">${e.latencyMs}ms</span></td>
                <td><div class="hash-link">${esc(String(e.previousHash || '').slice(0, 10))} → <strong>${esc(String(e.entryHash || '').slice(0, 12))}</strong></div></td>
            </tr>
            ${auditDetailRow(e)}
        `).join('') || `<tr><td colspan="5">${filtered ? 'No audit entries match these filters.' : 'No audit entries found.'}</td></tr>`;
        if (rows.length === 1 && callId) {
            toggleAuditDetail(Number(rows[0].sequenceNumber));
        }
    }

    /** QA3: who, on whose behalf, what it did (redacted arguments) and which rule decided it. */
    function auditDetailRow(e) {
        let args = e.redactedArgumentsJson || '{}';
        try {
            args = JSON.stringify(JSON.parse(args), null, 2);
        } catch {
            // keep the stored text as it is
        }
        const approval = e.approvalId ? `${e.approvalId} (${e.approvalState || '—'})` : '—';
        const when = e.occurredAt ? new Date(e.occurredAt).toLocaleString() : '—';
        return `
            <tr class="audit-detail" id="audit-detail-${Number(e.sequenceNumber)}" hidden>
                <td colspan="5">
                    <div class="audit-detail-grid">
                        <div><div class="meta-k">Call ID</div><div class="mono hash-full">${esc(e.id)}</div></div>
                        <div><div class="meta-k">Trace ID</div><div class="mono hash-full">${esc(e.traceId || '—')}</div></div>
                        <div><div class="meta-k">Time</div><div class="mono" title="${esc(e.occurredAt)}">${esc(when)}</div></div>
                        <div><div class="meta-k">Approval</div><div class="mono hash-full">${esc(approval)}</div></div>
                    </div>
                    <div class="meta-k">Redacted Arguments</div>
                    <pre class="code-well audit-args">${esc(args)}</pre>
                    <div class="meta-k">Entry Hash</div>
                    <div class="mono hash-full">${esc(e.entryHash)}</div>
                </td>
            </tr>`;
    }

    function toggleAuditDetail(sequenceNumber) {
        const row = document.getElementById(`audit-detail-${sequenceNumber}`);
        if (row) row.hidden = !row.hidden;
    }

    /** QA3: jumps from a governed call in the simulator to its audit record. */
    async function findCallInAudit() {
        if (!lastCallId) return;
        if (!getAuthHeader()) {
            showToast('Unlock the Operator Console to search the audit trail');
            return;
        }
        Object.values(AUDIT_FILTERS).forEach(id => { document.getElementById(id).value = ''; });
        document.getElementById('audit-callid-filter').value = lastCallId;
        switchTab('audit');
        await searchAudit();
    }

    async function verifyAuditChain(quiet = false) {
        const r = await apiFetch('/api/v1/audit/verify');
        if (!r.ok || !r.data) return;
        const v = r.data;
        const kpi = document.getElementById('kpi-audit');
        kpi.textContent = v.intact ? `${v.totalRecordsVerified} INTACT` : `TAMPERED #${v.firstTamperedSequenceNumber}`;
        kpi.style.color = v.intact ? 'var(--ok-ink)' : 'var(--err-ink)';

        const banner = document.getElementById('audit-verify-banner');
        banner.style.display = 'block';
        banner.innerHTML = `<span class="badge ${v.intact ? 'badge-ok' : 'badge-err'}">${v.intact ? 'INTACT' : 'TAMPER DETECTED'}</span>
            <span style="margin-left:8px;font-size:12.5px;">${esc(v.failureReason)} (${v.totalRecordsVerified} records verified)</span>`;
        if (!quiet) showToast(v.intact ? `Verified ${v.totalRecordsVerified} records intact` : 'Hash tampering detected!');
    }

    async function downloadAuditCsv() {
        // FR7: export exactly what the current search matches (all pages)
        const params = auditFilterParams();
        const callId = params.get('callId');
        if (callId && !UUID_PATTERN.test(callId)) {
            showToast('Call ID must be the UUID returned by the gateway');
            return;
        }
        const query = params.toString();
        const r = await apiFetch(`/api/v1/audit/export${query ? `?${query}` : ''}`);
        if (!r.ok) {
            showToast('CSV export failed');
            return;
        }
        const blob = new Blob([r.raw], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = query ? 'agentgate-audit-filtered.csv' : 'agentgate-audit.csv';
        a.click();
        URL.revokeObjectURL(url);
    }

    window.addEventListener('DOMContentLoaded', () => {
        checkHealth();
        if (getAuthHeader()) {
            unlockOperator(true);
        }
    });

    return {
        switchTab,
        applyPreset,
        executeGatewayCall,
        testPlayground,
        loadSampleDraft,
        clearDraft,
        unlockOperator,
        refreshOperatorData,
        toggleTool,
        toggleGlobalWriteKill,
        reloadPolicies,
        toggleAgent,
        loadApprovals,
        decideApproval,
        loadAudit,
        searchAudit,
        clearAuditSearch,
        auditPage,
        toggleAuditDetail,
        findCallInAudit,
        verifyAuditChain,
        downloadAuditCsv
    };
})();
