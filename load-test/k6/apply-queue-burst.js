import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL =
  __ENV.BASE_URL || 'http://host.docker.internal:18080';

const USERS = Number(__ENV.USERS || 100);
const CAPACITY = Number(__ENV.CAPACITY || 100);

/*
 * POST /apply 요청만 따로 측정하는 metric
 *
 * 전체 http_req_duration에는
 * 이벤트 생성, Queue 조회, Event 조회 등이 포함되므로
 * 실제 신청 API 성능을 별도로 측정한다.
 */
const applyDuration = new Trend(
  'apply_duration',
  true
);

export const options = {
  scenarios: {
    ticket_open_burst: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: 1,
      maxDuration: '30s',
    },
  },

  thresholds: {
    /*
     * 전체 HTTP가 아니라
     * 실제 POST /apply p95를 성능 기준으로 사용
     */
    apply_duration: ['p(95)<1000'],

    http_req_failed: ['rate<0.01'],
  },

  /*
   * custom Trend 결과에서
   * avg / median / p90 / p95 / max를 모두 확인
   */
  summaryTrendStats: [
    'avg',
    'min',
    'med',
    'p(90)',
    'p(95)',
    'max',
  ],
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
      'Content-Type':
        'application/json; charset=UTF-8',
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
  const applicantName =
    `burst-user-${__VU}`;

  const payload = JSON.stringify({
    applicantName: applicantName,
  });

  const params = {
    headers: {
      'Content-Type':
        'application/json; charset=UTF-8',
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

  /*
   * 실제 /apply 요청 시간만 별도 기록
   */
  applyDuration.add(
    res.timings.duration
  );

  /*
   * 실패한 요청의 원인을
   * 터미널에 상세 출력
   */
  if (
    res.status !== 200 &&
    res.status !== 201
  ) {
    console.error(
      `[APPLY FAILED] ` +
      `VU=${__VU} ` +
      `status=${res.status} ` +
      `error=${res.error || 'none'} ` +
      `error_code=${res.error_code || 'none'} ` +
      `duration=${res.timings.duration}ms ` +
      `body=${res.body || 'empty'}`
    );
  }

  check(res, {
    'apply request accepted': (r) =>
      r.status === 200 ||
      r.status === 201,

    'application status is PENDING':
      (r) => {

        if (
          r.status !== 200 &&
          r.status !== 201
        ) {
          return false;
        }

        try {
          return (
            r.json('status') ===
            'PENDING'
          );
        } catch (e) {
          console.error(
            `[INVALID RESPONSE] ` +
            `VU=${__VU} ` +
            `status=${r.status} ` +
            `body=${r.body || 'empty'}`
          );

          return false;
        }
      },
  });
}

export function teardown(data) {

  /*
   * Worker가 Queue를 모두 처리할 때까지
   * 최대 30초 기다린다.
   */
  for (let i = 0; i < 30; i++) {

    const queueRes = http.get(
      `${BASE_URL}/api/queue/applications/size`
    );

    let queueSize;

    try {
      queueSize =
        Number(queueRes.json('size'));
    } catch (e) {
      console.error(
        `[INVALID QUEUE RESPONSE] ` +
        `body=${queueRes.body || 'empty'}`
      );

      queueSize = -1;
    }

    console.log(
      `Queue size [${i + 1}s]: ${queueSize}`
    );

    /*
     * 기존 코드의
     * Number(queueRes.body) === 0
     * 문제 수정
     */
    if (queueSize === 0) {
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

  let finalQueueSize = -1;

  try {
    finalQueueSize =
      Number(queueRes.json('size'));
  } catch (e) {
    console.error(
      `[INVALID FINAL QUEUE RESPONSE] ` +
      `body=${queueRes.body || 'empty'}`
    );
  }

  console.log('');
  console.log(
    '================================'
  );
  console.log(
    ' FINAL TEST RESULT'
  );
  console.log(
    '================================'
  );
  console.log(`USERS: ${USERS}`);
  console.log(`CAPACITY: ${CAPACITY}`);
  console.log(
    `FINAL QUEUE SIZE: ${finalQueueSize}`
  );
  console.log(
    `EVENT: ${eventRes.body}`
  );
  console.log(
    '================================'
  );

  check(eventRes, {
    'capacity not exceeded': (r) =>
      Number(
        r.json('appliedCount')
      ) <= CAPACITY,

    'capacity fully allocated': (r) =>
      Number(
        r.json('appliedCount')
      ) === CAPACITY,
  });

  /*
   * Queue가 최종적으로 비워졌는지도 검증
   */
  check(queueRes, {
    'queue fully drained': () =>
      finalQueueSize === 0,
  });
}