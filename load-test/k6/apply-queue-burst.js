import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL =
  __ENV.BASE_URL || 'http://host.docker.internal:18080';

const USERS = Number(__ENV.USERS || 100);
const CAPACITY = Number(__ENV.CAPACITY || 100);

export const options = {
  scenarios: {
    ticket_open_burst: {
      executor: 'per-vu-iterations',

      // USERS명의 사용자가
      vus: USERS,

      // 각자 딱 1회 신청
      iterations: 1,

      maxDuration: '30s',
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

export function setup() {
  const payload = JSON.stringify({
    title: `FastPass Burst Test ${Date.now()}`,
    description: 'k6 ticket open burst test',
    capacity: CAPACITY,
    eventStartAt: '2026-12-31T10:00:00',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
  };

  const res = http.post(
    `${BASE_URL}/api/events`,
    payload,
    params
  );

  check(res, {
    'event created': (r) =>
      r.status === 200 || r.status === 201,
  });

  return {
    eventId: res.json('id'),
  };
}

export default function (data) {
  const applicantName = `burst-user-${__VU}`;

  const payload = JSON.stringify({
    applicantName: applicantName,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
    },
    tags: {
      name: 'POST /api/events/:id/apply',
    },
  };

  const res = http.post(
    `${BASE_URL}/api/events/${data.eventId}/apply`,
    payload,
    params
  );

  check(res, {
    'apply request accepted': (r) =>
      r.status === 200 || r.status === 201,

    'application status is PENDING': (r) =>
      r.json('status') === 'PENDING',
  });
}

export function teardown(data) {
  // Worker가 Queue를 모두 처리할 때까지 기다림
  for (let i = 0; i < 30; i++) {
    const queueRes = http.get(
      `${BASE_URL}/api/queue/applications/size`
    );

    console.log(
      `Queue size [${i + 1}s]: ${queueRes.body}`
    );

    if (Number(queueRes.body) === 0) {
      break;
    }

    sleep(1);
  }

  const queueRes = http.get(
    `${BASE_URL}/api/queue/applications/size`
  );

  const eventRes = http.get(
    `${BASE_URL}/api/events/${data.eventId}`
  );

  console.log('');
  console.log('================================');
  console.log(' FINAL TEST RESULT');
  console.log('================================');
  console.log(`USERS: ${USERS}`);
  console.log(`CAPACITY: ${CAPACITY}`);
  console.log(`FINAL QUEUE SIZE: ${queueRes.body}`);
  console.log(`EVENT: ${eventRes.body}`);
  console.log('================================');

  check(eventRes, {
    'capacity not exceeded': (r) =>
      Number(r.json('appliedCount')) <= CAPACITY,

    'capacity fully allocated': (r) =>
      Number(r.json('appliedCount')) === CAPACITY,
  });
}