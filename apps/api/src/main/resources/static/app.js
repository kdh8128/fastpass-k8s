const $ = (id) => document.getElementById(id);

function normalizeDateTime(value) {
  if (!value) {
    return value;
  }

  if (value.length === 16) {
    return `${value}:00`;
  }

  return value;
}

function setResult(elementId, message, type = "") {
  const element = $(elementId);
  element.className = `result ${type}`;
  element.innerHTML = message;
}

function toJsonBlock(data) {
  return `<pre>${JSON.stringify(data, null, 2)}</pre>`;
}

function statusBadge(status) {
  const normalized = String(status || "UNKNOWN").toLowerCase();
  return `<span class="badge ${normalized}">${status}</span>`;
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      "Content-Type": "application/json; charset=UTF-8",
      ...(options.headers || {}),
    },
    ...options,
  });

  const text = await response.text();
  let data = null;

  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!response.ok) {
    const message =
      data && data.message
        ? data.message
        : `Request failed. status=${response.status}`;

    throw new Error(message);
  }

  return data;
}

async function refreshStatus() {
  try {
    const health = await request("/actuator/health");
    $("healthStatus").textContent = health.status || "UNKNOWN";
  } catch {
    $("healthStatus").textContent = "DOWN";
  }

  try {
    const queue = await request("/api/queue/applications/size");
    $("queueSize").textContent = queue.size ?? "-";
  } catch {
    $("queueSize").textContent = "-";
  }
}

async function refreshEvents() {
  const eventList = $("eventList");
  eventList.innerHTML = `<p>이벤트를 불러오는 중...</p>`;

  try {
    const events = await request("/api/events");

    if (!events || events.length === 0) {
      eventList.innerHTML = `<p>생성된 이벤트가 없습니다.</p>`;
      return;
    }

    eventList.innerHTML = events
      .map((event) => {
        const remaining = event.capacity - event.appliedCount;

        return `
          <article class="event-card">
            <h3>${event.title}</h3>
            <p>${event.description || ""}</p>
            <div class="event-meta">
              <span>ID: <strong>${event.id}</strong></span>
              <span>Capacity: <strong>${event.capacity}</strong></span>
              <span>Applied: <strong>${event.appliedCount}</strong></span>
              <span>Remaining: <strong>${remaining}</strong></span>
              <span>Start: <strong>${event.eventStartAt || "-"}</strong></span>
              <span>${remaining > 0 ? statusBadge("READY") : statusBadge("FAILED")}</span>
            </div>
          </article>
        `;
      })
      .join("");
  } catch (error) {
    eventList.innerHTML = `<p class="result error">${error.message}</p>`;
  }
}

function setDefaultDateTime() {
  const now = new Date();
  now.setDate(now.getDate() + 1);
  now.setHours(10, 0, 0, 0);

  const yyyy = now.getFullYear();
  const mm = String(now.getMonth() + 1).padStart(2, "0");
  const dd = String(now.getDate()).padStart(2, "0");
  const hh = String(now.getHours()).padStart(2, "0");
  const mi = String(now.getMinutes()).padStart(2, "0");

  $("eventStartAt").value = `${yyyy}-${mm}-${dd}T${hh}:${mi}`;
}

$("eventForm").addEventListener("submit", async (event) => {
  event.preventDefault();

  const payload = {
    title: $("eventTitle").value,
    description: $("eventDescription").value,
    capacity: Number($("eventCapacity").value),
    eventStartAt: normalizeDateTime($("eventStartAt").value),
  };

  try {
    const data = await request("/api/events", {
      method: "POST",
      body: JSON.stringify(payload),
    });

    $("applyEventId").value = data.id;

    setResult(
      "eventCreateResult",
      `이벤트 생성 완료<br/>${toJsonBlock(data)}`,
      "success"
    );

    await refreshEvents();
    await refreshStatus();
  } catch (error) {
    setResult("eventCreateResult", error.message, "error");
  }
});

$("applyForm").addEventListener("submit", async (event) => {
  event.preventDefault();

  const eventId = $("applyEventId").value;

  const payload = {
    applicantName: $("applicantName").value,
  };

  try {
    const data = await request(`/api/events/${eventId}/apply`, {
      method: "POST",
      body: JSON.stringify(payload),
    });

    $("applicationId").value = data.id;

    setResult(
      "applyResult",
      `신청 요청 완료 ${statusBadge(data.status)}<br/>${toJsonBlock(data)}`,
      "success"
    );

    await refreshEvents();
    await refreshStatus();
  } catch (error) {
    setResult("applyResult", error.message, "error");
  }
});

$("applicationLookupForm").addEventListener("submit", async (event) => {
  event.preventDefault();

  const applicationId = $("applicationId").value;

  try {
    const data = await request(`/api/applications/${applicationId}`);

    setResult(
      "applicationResult",
      `현재 상태 ${statusBadge(data.status)}<br/>${toJsonBlock(data)}`,
      "success"
    );

    await refreshStatus();
  } catch (error) {
    setResult("applicationResult", error.message, "error");
  }
});

$("refreshEventsBtn").addEventListener("click", refreshEvents);
$("eventListRefreshBtn").addEventListener("click", refreshEvents);
$("refreshStatusBtn").addEventListener("click", refreshStatus);

setDefaultDateTime();
refreshStatus();
refreshEvents();

setInterval(refreshStatus, 5000);