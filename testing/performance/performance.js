import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { isAccepted, makeEvent } from './event.js';

const scenario = (__ENV.SCENARIO || 'smoke').toLowerCase();
const scenarioConfigs = {
  smoke: {
    pacingSeconds: 0.1,
    options: {
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '30s',
      thresholds: {
        http_req_failed: ['rate<0.05'],
        pulseflow_api_failures: ['rate<0.05'],
        http_req_duration: ['p(95)<1000', 'p(99)<2000'],
      },
    },
  },
  load: {
    pacingSeconds: 0.05,
    options: {
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
    },
  },
  stress: {
    pacingSeconds: 0.02,
    options: {
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
    },
  },
};

const selectedConfig = scenarioConfigs[scenario];
if (!selectedConfig) {
  throw new Error(`Unsupported SCENARIO=${scenario}; use smoke, load, or stress.`);
}
if (scenario === 'stress' && (__ENV.ALLOW_STRESS || '').toLowerCase() !== 'true') {
  throw new Error('Stress is manual only. Re-run with -e ALLOW_STRESS=true after confirming the target is disposable.');
}

const apiFailures = new Rate('pulseflow_api_failures');
const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const pacingSeconds = Number(__ENV.PACE_SECONDS || selectedConfig.pacingSeconds);

export const options = selectedConfig.options;

export default function () {
  const response = http.post(
    `${baseUrl}/api/events`,
    JSON.stringify(makeEvent(__ITER)),
    {
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      tags: { scenario },
    },
  );
  const accepted = isAccepted(response);
  apiFailures.add(!accepted);
  check(response, {
    'event ingress returns HTTP 200': (res) => res.status === 200,
    'ApiResponse code and accepted flag are valid': () => accepted,
  });
  if (pacingSeconds > 0) sleep(pacingSeconds);
}
