import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Three scenarios run in parallel for ~3 minutes:
//   browse   - ramping users hitting /hello and /lab/chain (request rate, latency, traces)
//   allocate - steady young-gen churn: 20 MB in 64 KB chunks every 2s (never humongous)
//   leak     - two leak cycles: grow old gen for 20s, stop, clear, let GC reclaim it
export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      exec: 'browse',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '2m', target: 10 },
        { duration: '30s', target: 0 },
      ],
    },
    allocate: {
      executor: 'constant-arrival-rate',
      exec: 'allocate',
      rate: 1,
      timeUnit: '2s',
      duration: '3m',
      preAllocatedVUs: 1,
      maxVUs: 2,
    },
    leak: {
      executor: 'per-vu-iterations',
      exec: 'leakCycle',
      vus: 1,
      iterations: 2,
      startTime: '20s',
      maxDuration: '3m',
    },
  },
  thresholds: {
    'http_req_failed{scenario:browse}': ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<500'],
  },
};

export function browse() {
  const hello = http.get(`${BASE_URL}/hello`, { tags: { name: 'hello' } });
  check(hello, { 'hello is 200': (r) => r.status === 200 });

  const chain = http.get(`${BASE_URL}/lab/chain`, { tags: { name: 'chain' } });
  check(chain, { 'chain is 200': (r) => r.status === 200 });

  sleep(1);
}

export function allocate() {
  const res = http.get(`${BASE_URL}/lab/allocate?mb=20&chunkKb=64`, { tags: { name: 'allocate' } });
  check(res, { 'allocate is 200': (r) => r.status === 200 });
}

export function leakCycle() {
  http.post(`${BASE_URL}/lab/leak/start`, null, { tags: { name: 'leak-start' } });
  sleep(20);

  const status = http.get(`${BASE_URL}/lab/leak/status`, { tags: { name: 'leak-status' } });
  check(status, { 'leak is running': (r) => r.json('running') === true });

  http.post(`${BASE_URL}/lab/leak/stop`, null, { tags: { name: 'leak-stop' } });
  sleep(15);

  http.post(`${BASE_URL}/lab/leak/clear`, null, { tags: { name: 'leak-clear' } });
  sleep(25);
}
