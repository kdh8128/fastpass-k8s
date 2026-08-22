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
    const status = health.status || "UNKNOWN";

    $("healthStatus").textContent = status;
    $("healthStatusSub").textContent = status;
  } catch {
    $("healthStatus").textContent = "DOWN";
    $("healthStatusSub").textContent = "DOWN";
  }

  try {
    const queue = await request("/api/queue/applications/size");
    const size = queue.size ?? "-";

    $("queueSize").textContent = size;
    $("queueSizeSub").textContent = size;
  } catch {
    $("queueSize").textContent = "-";
    $("queueSizeSub").textContent = "-";
  }
}

async function refreshEvents() {
  const eventList = $("eventList");
  eventList.innerHTML = `<div class="empty">이벤트를 불러오는 중입니다...</div>`;

  try {
    const events = await request("/api/events");

    if (!events || events.length === 0) {
      eventList.innerHTML = `
        <div class="empty">
          아직 생성된 이벤트가 없습니다. 상단에서 이벤트를 먼저 생성해보세요.
        </div>
      `;
      updateFeaturedEvent(null);
      return;
    }

    const sortedEvents = [...events].sort((a, b) => b.id - a.id);
    updateFeaturedEvent(sortedEvents[0]);

    eventList.innerHTML = sortedEvents
      .map((event) => {
        const remaining = Math.max(event.capacity - event.appliedCount, 0);
        const status = remaining > 0 ? "READY" : "FAILED";

        return `
          <article class="event-card">
            ${statusBadge(status)}
            <h3>${escapeHtml(event.title)}</h3>
            <p>${escapeHtml(event.description || "이벤트 설명이 없습니다.")}</p>

            <div class="event-meta">
              <div>
                <span>Event ID</span>
                <strong>${event.id}</strong>
              </div>
              <div>
                <span>Capacity</span>
                <strong>${event.capacity}</strong>
              </div>
              <div>
                <span>Applied</span>
                <strong>${event.appliedCount}</strong>
              </div>
              <div>
                <span>Remaining</span>
                <strong>${remaining}</strong>
              </div>
            </div>
          </article>
        `;
      })
      .join("");
  } catch (error) {
    eventList.innerHTML = `<div class="empty">${error.message}</div>`;
  }
}

function updateFeaturedEvent(event) {
  if (!event) {
    $("featuredTitle").textContent = "FastPass Open Event";
    $("featuredCapacity").textContent = "3";
    $("featuredApplied").textContent = "0";
    $("featuredRemaining").textContent = "3";
    return;
  }

  $("featuredTitle").textContent = event.title;
  $("featuredCapacity").textContent = event.capacity;
  $("featuredApplied").textContent = event.appliedCount;
  $("featuredRemaining").textContent = Math.max(event.capacity - event.appliedCount, 0);
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

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
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
      `이벤트가 생성되었습니다. Event ID: <strong>${data.id}</strong>${toJsonBlock(data)}`,
      "success"
    );

    await refreshEvents();
    await refreshStatus();

    document.getElementById("events").scrollIntoView({ behavior: "smooth" });
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
      `신청이 접수되었습니다. ${statusBadge(data.status)}${toJsonBlock(data)}`,
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
      `현재 신청 상태: ${statusBadge(data.status)}${toJsonBlock(data)}`,
      "success"
    );

    await refreshStatus();
  } catch (error) {
    setResult("applicationResult", error.message, "error");
  }
});

$("eventListRefreshBtn").addEventListener("click", refreshEvents);
$("refreshStatusBtn").addEventListener("click", refreshStatus);

$("heroCreateBtn").addEventListener("click", () => {
  document.querySelector(".form-card").scrollIntoView({ behavior: "smooth" });
});

$("heroApplyBtn").addEventListener("click", () => {
  document.getElementById("apply").scrollIntoView({ behavior: "smooth" });
});

setDefaultDateTime();
refreshStatus();
refreshEvents();

setInterval(refreshStatus, 5000);