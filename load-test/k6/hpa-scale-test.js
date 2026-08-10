import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

export const options = {
  scenarios: {
    hpa_scale_test: {
      executor: 'constant-vus',
      vus: 30,
      duration: '90s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:18080';

export function setup() {
  const params = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
  };

  for (let attempt = 1; attempt <= 5; attempt++) {
    const payload = JSON.stringify({
      title: `FastPass HPA Test ${Date.now()}`,
      description: 'hpa scale out test',
      capacity: 100000,
      eventStartAt: '2026-07-20T10:00:00',
    });

    const res = http.post(`${BASE_URL}/api/events`, payload, params);

    const ok = check(res, {
      'event created': (r) => r.status === 200 || r.status === 201,
    });

    if (ok) {
      return {
        eventId: res.json('id'),
      };
    }

    console.error(`Failed to create event. attempt=${attempt}, status=${res.status}, error=${res.error}, body=${res.body}`);
    sleep(2);
  }

  exec.test.abort('Failed to create event after 5 attempts.');
}

export default function (data) {
  const payload = JSON.stringify({
    applicantName: `hpa-user-${__VU}-${__ITER}`,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
  };

  const res = http.post(`${BASE_URL}/api/events/${data.eventId}/apply`, payload, params);

  check(res, {
    'apply accepted': (r) => r.status === 200 || r.status === 201,
  });

  sleep(0.1);
}

export function teardown(data) {
  const queueRes = http.get(`${BASE_URL}/api/queue/applications/size`);
  console.log(`Final queue size response: ${queueRes.body}`);

  const eventRes = http.get(`${BASE_URL}/api/events/${data.eventId}`);
  console.log(`Event response: ${eventRes.body}`);
}