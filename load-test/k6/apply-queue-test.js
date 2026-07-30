import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    apply_queue_load: {
      executor: 'constant-vus',
      vus: 20,
      duration: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:18080';

export function setup() {
  const payload = JSON.stringify({
    title: `FastPass k6 Event ${Date.now()}`,
    description: 'k6 queue load test',
    capacity: 10000,
    eventStartAt: '2026-07-20T10:00:00',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
  };

  const res = http.post(`${BASE_URL}/api/events`, payload, params);

  check(res, {
    'event created': (r) => r.status === 200 || r.status === 201,
  });

  const body = res.json();

  return {
    eventId: body.id,
  };
}

export default function (data) {
  const applicantName = `k6-user-${__VU}-${__ITER}`;

  const payload = JSON.stringify({
    applicantName: applicantName,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
  };

  const res = http.post(`${BASE_URL}/api/events/${data.eventId}/apply`, payload, params);

  check(res, {
    'apply request accepted': (r) => r.status === 200 || r.status === 201,
    'application status is PENDING': (r) => r.json('status') === 'PENDING',
  });

  sleep(1);
}

export function teardown(data) {
  const queueRes = http.get(`${BASE_URL}/api/queue/applications/size`);
  console.log(`Final queue size response: ${queueRes.body}`);

  const eventRes = http.get(`${BASE_URL}/api/events/${data.eventId}`);
  console.log(`Event response: ${eventRes.body}`);
}
