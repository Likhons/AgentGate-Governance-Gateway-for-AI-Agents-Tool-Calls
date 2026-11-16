import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

/**
 * QA2 Performance & QA1 Security Load Test Script (k6)
 * Verifies that AgentGate adds <= 30 ms at p95 at a sustained load of 100 calls/second,
 * excluding human approval waits (QA2).
 *
 * Run command:
 *   k6 run load-tests/qa2_gateway_latency.k6.js
 */
const gatewayOverheadTrend = new Trend('agentgate_gateway_overhead_ms', true);
const legitimatePassRate = new Rate('qa1_legitimate_pass_rate');

export const options = {
    scenarios: {
        sustained_100_rps: {
            executor: 'constant-arrival-rate',
            rate: 100,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 30,
            maxVUs: 100,
        },
    },
    thresholds: {
        // QA2: Gateway overhead metric <= 30 ms at p95 at 100 calls/sec
        'agentgate_gateway_overhead_ms': ['p(95)<30'],
        'http_req_duration': ['p(95)<40'],
        // QA1: No more than 5% of legitimate calls wrongly blocked (>= 95% pass rate)
        'qa1_legitimate_pass_rate': ['rate>=0.95'],
    },
};

const BASE_URL = __ENV.AGENTGATE_URL || 'http://localhost:8080';
const DOWNSTREAM_URL = __ENV.DOWNSTREAM_URL || 'http://mock-downstream:8081';
const ADMIN_AUTH = encoding.b64encode(`${__ENV.ADMIN_USER || 'user'}:${__ENV.ADMIN_PASS || 'password'}`);

export function setup() {
    const adminHeaders = {
        'Content-Type': 'application/json',
        'Authorization': `Basic ${ADMIN_AUTH}`,
    };
    const clientId = __ENV.CLIENT_ID || 'support-bot';
    const clientSecret = __ENV.CLIENT_SECRET || 'support-secret-2026';

    // Ensure baseline agent, tool, policy, and benchmark throughput limits exist (idempotent)
    http.post(`${BASE_URL}/api/v1/agents`, JSON.stringify({
        clientId,
        clientSecret,
        agentName: 'Customer Support Agent',
        owningTeam: 'SupportOps',
        clearanceLevel: 'NONE',
        scopes: 'mcp:invoke',
    }), { headers: adminHeaders });

    http.post(`${BASE_URL}/api/v1/tools`, JSON.stringify({
        name: 'lookup_order',
        description: 'Read customer order by customerId',
        inputSchema: '{"type":"object","properties":{"customerId":{"type":"string"}},"required":["customerId"]}',
        riskLevel: 'LOW',
        owningTeam: 'OrderTeam',
        toolType: 'REST',
        writeOperation: false,
        downstreamUrl: `${DOWNSTREAM_URL}/orders/{customerId}`,
        httpMethod: 'GET',
        timeoutMs: 2000,
    }), { headers: adminHeaders });

    http.post(`${BASE_URL}/api/v1/policies`, JSON.stringify({
        ruleName: 'allow-own-customer-order-read',
        priority: 10,
        agentPattern: clientId,
        toolPattern: 'lookup_order',
        effect: 'ALLOW',
        conditions: [{ field: 'customerId', operator: 'MATCHES_DELEGATED_USER', expectedValue: '$user' }],
        dryRun: false,
        description: 'Allow agent to read only the requesting customer order',
    }), { headers: adminHeaders });

    http.put(`${BASE_URL}/api/v1/limits`, JSON.stringify({
        agentClientId: clientId,
        toolName: 'lookup_order',
        requestsPerMinute: 20000,
        dailyQuota: 1000000,
        writeBlastRadiusPerHour: 10000,
    }), { headers: adminHeaders });

    // Obtain OAuth2 Client Credentials JWT once during test setup (FR2)
    const tokenRes = http.post(
        `${BASE_URL}/oauth2/token`,
        {
            grant_type: 'client_credentials',
            client_id: clientId,
            client_secret: clientSecret,
            delegated_user: 'cust-100',
        },
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }
    );

    return { token: tokenRes.json('access_token') };
}

export default function (data) {
    const payload = JSON.stringify({
        toolName: 'lookup_order',
        delegatedUserId: 'cust-100',
        arguments: {
            customerId: 'cust-100',
        },
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${data.token}`,
            'X-On-Behalf-Of-User': 'cust-100',
        },
    };

    const res = http.post(`${BASE_URL}/api/v1/gateway/call`, payload, params);

    const passed = check(res, {
        'status is 200': (r) => r.status === 200,
        'outcome is EXECUTED': (r) => r.status === 200 && r.json('outcome') === 'EXECUTED',
    });

    legitimatePassRate.add(passed);
    if (res.status === 200 && res.json('gatewayLatencyMs') !== undefined) {
        gatewayOverheadTrend.add(res.json('gatewayLatencyMs'));
    } else {
        gatewayOverheadTrend.add(res.timings.duration);
    }
}
