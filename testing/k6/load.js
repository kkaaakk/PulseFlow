import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { makeEvent } from './scenarios/event.js';

const apiFailures = new Rate('pulseflow_api_failures');
const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const pacingSeconds = Number(__ENV.PACE_SECONDS || 0.05);

export const options = {
  stages: [
    { duration: '20s', target: 10 },
    { duration: '30s', target: 50 },
    { duration: '30s', target: 100 },
    { duration: '30s', target: 200 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.10'],
    pulseflow_api_failures: ['rate<0.10'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
  },
};

export default function () {
  const response = http.post(
    `${baseUrl}/api/events`,
    JSON.stringify(makeEvent(__ITER)),
    { headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, tags: { scenario: 'load' } },
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
  check(response, { 'load event accepted': () => accepted });
  if (pacingSeconds > 0) sleep(pacingSeconds);
}
