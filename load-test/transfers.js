// k6 load test that behaves like real wallet users.
// Run with scripts\load-test.ps1: it starts an isolated copy of the system, runs one test per load level
// (10, 25, 50, 100, 200 concurrent users by default) and checks the data afterwards.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://wallet-service:8081';
const USERS = Number(__ENV.USERS || 200);
const MERCHANTS = 3;            // users 0-2 act as popular shops that receive many payments (hot rows)
const RUN = Date.now();

export const options = {
    setupTimeout: '5m',
    scenarios: {
        users: { executor: 'constant-vus', vus: Number(__ENV.VUS || 50), duration: __ENV.DURATION || '45s' },
    },
    thresholds: {
        unexpected_errors: ['count==0'],      // anything except success, insufficient funds or "busy, retry"
        double_charges: ['count==0'],         // a double-click must never charge twice
        // these only make k6 report the per-endpoint latency; the runner records the numbers
        'http_req_duration{name:transfer}': ['p(95)<60000'],
        'http_req_duration{name:read}': ['p(95)<60000'],
        'http_req_duration{name:topup}': ['p(95)<60000'],
    },
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const transfersOk = new Counter('transfers_ok');
const insufficientFunds = new Counter('transfers_insufficient_funds');
const replays = new Counter('idempotent_replays');
const busyRetries = new Counter('busy_retries');          // got 503, waited, retried with the same key
const shed = new Counter('requests_shed');                 // still 503 after 3 tries (the user would see "try later")
const doubleCharges = new Counter('double_charges');
const unexpected = new Counter('unexpected_errors');
let loggedErrors = 0;
const logError = (msg) => { if (loggedErrors++ < 5) console.error(msg); };

const JSON_HEADERS = { 'Content-Type': 'application/json' };
const auth = (token, key) => key
    ? { ...JSON_HEADERS, Authorization: `Bearer ${token}`, 'Idempotency-Key': key }
    : { Authorization: `Bearer ${token}` };
// __ITER does not exist during setup(), only in the test iterations
const newKey = () => `k6-${__VU}-${typeof __ITER === 'undefined' ? 's' : __ITER}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
const pick = (n) => Math.floor(Math.random() * n);
const thinkTime = () => sleep(0.5 + Math.random());   // a real person pauses between actions

/** Creates the users (in parallel batches) and gives each ₹5,000. */
export function setup() {
    const users = [];
    for (let start = 0; start < USERS; start += 20) {
        const batch = [];
        for (let i = start; i < Math.min(start + 20, USERS); i++) {
            const email = `load-${RUN}-${i}@load.dev`;
            users.push({ email });
            batch.push(['POST', `${BASE}/api/auth/register`,
                JSON.stringify({ email, password: 'Secret123', fullName: `Load User ${i}` }), { headers: JSON_HEADERS }]);
        }
        http.batch(batch).forEach((res, j) => {
            if (res.status !== 201) throw new Error(`register failed: ${res.status} ${res.body}`);
            users[start + j].token = res.json('accessToken');
        });
        const topUps = users.slice(start, start + batch.length).map((u) => ['POST', `${BASE}/api/wallets/me/top-ups`,
            JSON.stringify({ amount: 5000 }), { headers: auth(u.token, newKey()) }]);
        http.batch(topUps).forEach((res) => {
            if (res.status !== 201) throw new Error(`top-up failed: ${res.status} ${res.body}`);
        });
    }
    return { users };
}

/** Sends a money request the way a real app does: on "503 busy" wait and retry with the SAME key. */
function sendWithRetry(url, body, token, key, name) {
    let res;
    for (let attempt = 1; attempt <= 3; attempt++) {
        res = http.post(url, body, { headers: auth(token, key), tags: { name } });
        if (res.status !== 503) return res;
        if (attempt < 3) {
            busyRetries.add(1);
            sleep(Number(res.headers['Retry-After'] || 1));
        }
    }
    shed.add(1);
    return res;
}

function record(res) {
    if (res.status === 201) transfersOk.add(1);
    else if (res.status === 422 && res.json('code') === 'INSUFFICIENT_FUNDS') insufficientFunds.add(1);
    else if (res.status === 503) { /* counted as shed */ }
    else {
        unexpected.add(1);
        logError(`unexpected ${res.status}: ${res.body}`);
    }
}

function transfer(users, me) {
    const toMerchant = Math.random() < 0.15;
    let to = toMerchant ? users[pick(MERCHANTS)] : users[pick(users.length)];
    // compare by email: k6 hands each VU its own copy of the setup data
    if (to.email === me.email) to = users[(users.findIndex((u) => u.email === me.email) + 1) % users.length];
    const body = JSON.stringify({ recipientEmail: to.email, amount: Number((1 + Math.random() * 600).toFixed(2)) });
    const url = `${BASE}/api/wallets/me/transfers`;
    const key = newKey();

    if (Math.random() < 0.03) {
        // double-click: the same request sent twice at the same moment
        const params = { headers: auth(me.token, key), tags: { name: 'transfer' } };
        const pair = http.batch([['POST', url, body, params], ['POST', url, body, params]]);
        if (pair.filter((r) => r.status === 201).length === 2) doubleCharges.add(1);
        pair.forEach((r) => {
            if (r.status === 200 && r.headers['Idempotent-Replayed'] === 'true') replays.add(1);
            else if (!(r.status === 409 && r.json('code') === 'REQUEST_IN_PROGRESS')) record(r);
        });
        return;
    }

    const res = sendWithRetry(url, body, me.token, key, 'transfer');
    record(res);
    if (res.status === 201 && Math.random() < 0.1) {
        // the app timed out and retries with the same key: must be replayed, never charged again
        const retry = sendWithRetry(url, body, me.token, key, 'transfer');
        const ok = check(retry, {
            'retry replayed, not charged again': (r) => r.status === 200 && r.headers['Idempotent-Replayed'] === 'true'
                && r.json('transactionId') === res.json('transactionId'),
        });
        if (ok) replays.add(1);
        else if (retry.status !== 503) {
            unexpected.add(1);
            logError(`retry not replayed: ${retry.status} ${retry.body}`);
        }
    }
}

export default function (data) {
    const users = data.users;
    const me = users[MERCHANTS + pick(users.length - MERCHANTS)];
    const action = Math.random();

    if (action < 0.6) {
        transfer(users, me);
    } else if (action < 0.9) {
        // check balance and recent activity
        const reads = http.batch([
            ['GET', `${BASE}/api/wallets/me`, null, { headers: auth(me.token), tags: { name: 'read' } }],
            ['GET', `${BASE}/api/wallets/me/statement?size=10`, null, { headers: auth(me.token), tags: { name: 'read' } }],
        ]);
        reads.forEach((r) => {
            if (r.status !== 200 && r.status !== 503) {
                unexpected.add(1);
                logError(`read failed: ${r.status} ${r.body}`);
            }
        });
    } else {
        const res = sendWithRetry(`${BASE}/api/wallets/me/top-ups`, JSON.stringify({ amount: 500 }), me.token, newKey(), 'topup');
        if (res.status !== 201 && res.status !== 503) {
            unexpected.add(1);
            logError(`top-up failed: ${res.status} ${res.body}`);
        }
    }
    thinkTime();
}
