import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { isAccepted, makeEvent } from './event.js';

const apiFailures = new Rate('pulseflow_api_failures');
const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const pacingSeconds = Number(__ENV.PACE_SECONDS || 0.1);

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    pulseflow_api_failures: ['rate<0.05'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
  },
};

export default function () {
  const response = http.post(
    `${baseUrl}/api/events`,
    JSON.stringify(makeEvent(__ITER)),
    { headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, tags: { scenario: 'smoke' } },
  );
  const accepted = isAccepted(response);
  apiFailures.add(!accepted);
  check(response, {
    'event ingress returns HTTP 200': (res) => res.status === 200,
    'ApiResponse code and accepted flag are valid': () => accepted,
  });
  if (pacingSeconds > 0) sleep(pacingSeconds);
}
