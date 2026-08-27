import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { makeEvent } from './scenarios/event.js';

if ((__ENV.ALLOW_STRESS || '').toLowerCase() !== 'true') {
  throw new Error('stress.js is manual only. Re-run with -e ALLOW_STRESS=true after confirming the target is disposable.');
}

const apiFailures = new Rate('pulseflow_api_failures');
const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');

export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '45s', target: 100 },
    { duration: '45s', target: 250 },
    { duration: '45s', target: 500 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.20'],
    pulseflow_api_failures: ['rate<0.20'],
    http_req_duration: ['p(95)<3000', 'p(99)<5000'],
  },
};

export default function () {
  const response = http.post(
    `${baseUrl}/api/events`,
    JSON.stringify(makeEvent(__ITER)),
    { headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, tags: { scenario: 'stress' } },
  );
  let body = null;
  try {
    body = JSON.parse(response.body);
  } catch (error) {
    // The semantic check below will mark this request as failed.
  }
  const accepted = response.status === 200 && body !== null && body.code === 200
    && body.data !== null && body.data.accepted === true;
  apiFailures.add(!accepted);
  check(response, { 'stress event accepted': () => accepted });
  sleep(Number(__ENV.PACE_SECONDS || 0.02));
}
