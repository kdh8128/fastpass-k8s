const $ = (id) => document.getElementById(id);

let selectedEvent = null;
let eventsCache = [];

function setTextIfExists(id, value) {
  const element = $(id);

  if (element) {
    element.textContent = value;
  }
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function normalizeDate(value) {
  if (!value) {
    return "-";
  }

  return String(value).replace("T", " ").slice(0, 16);
}

function badge(status) {
  const key = String(status).toLowerCase();

  return `<span class="badge ${key}">${status}</span>`;
}

function jsonBlock(data) {
  return `<pre>${JSON.stringify(data, null, 2)}</pre>`;
}

function setResult(id, message, type = "") {
  const element = $(id);

  if (!element) {
    return;
  }

  element.className = `result ${type}`;
  element.innerHTML = message;
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
    setTextIfExists("apiHealth", health.status || "UNKNOWN");
  } catch {
    setTextIfExists("apiHealth", "DOWN");
  }

  try {
    const queue = await request("/api/queue/applications/size");
    setTextIfExists("queueSize", queue.size ?? "-");
  } catch {
    setTextIfExists("queueSize", "-");
  }

  const openEvents = eventsCache.filter((event) => {
    const remaining = event.capacity - event.appliedCount;
    return remaining > 0;
  });

  setTextIfExists("openCount", openEvents.length);
}

async function refreshEvents() {
  const eventList = $("eventList");
  eventList.innerHTML = `<div class="empty">티켓팅 목록을 불러오는 중입니다...</div>`;

  try {
    const events = await request("/api/events");
    eventsCache = [...events].sort((a, b) => b.id - a.id);

    if (eventsCache.length === 0) {
      eventList.innerHTML = `
        <div class="empty">
          현재 오픈된 티켓팅이 없습니다.
          관리자 페이지에서 이벤트를 먼저 생성해주세요.
        </div>
      `;

      selectedEvent = null;
      renderSelectedEvent();
      await refreshStatus();
      return;
    }

    eventList.innerHTML = eventsCache.map(renderEventCard).join("");

    document.querySelectorAll("[data-select-event-id]").forEach((button) => {
      button.addEventListener("click", () => {
        const eventId = Number(button.dataset.selectEventId);
        selectedEvent = eventsCache.find((event) => event.id === eventId);
        renderSelectedEvent();
        document.getElementById("selected").scrollIntoView({ behavior: "smooth" });
      });
    });

    if (!selectedEvent) {
      selectedEvent =
        eventsCache.find((event) => event.capacity - event.appliedCount > 0) ||
        eventsCache[0];

      renderSelectedEvent();
    } else {
      const refreshedSelectedEvent = eventsCache.find(
        (event) => event.id === selectedEvent.id
      );

      selectedEvent = refreshedSelectedEvent || selectedEvent;
      renderSelectedEvent();
    }

    await refreshStatus();
  } catch (error) {
    eventList.innerHTML = `<div class="empty">${error.message}</div>`;
  }
}

function renderEventCard(event) {
  const remaining = Math.max(event.capacity - event.appliedCount, 0);
  const status = remaining > 0 ? "READY" : "FAILED";
  const usedRatio =
    event.capacity > 0
      ? Math.min((event.appliedCount / event.capacity) * 100, 100)
      : 0;

  const disabled = remaining <= 0 ? "disabled" : "";

  return `
    <article class="event-card">
      ${badge(status)}

      <h3>${escapeHtml(event.title)}</h3>

      <p>${escapeHtml(event.description || "이벤트 설명이 없습니다.")}</p>

      <div class="progress">
        <span style="width: ${usedRatio}%"></span>
      </div>

      <div class="event-meta">
        <div>
          <span>정원</span>
          <strong>${event.capacity}</strong>
        </div>
        <div>
          <span>신청</span>
          <strong>${event.appliedCount}</strong>
        </div>
        <div>
          <span>잔여</span>
          <strong>${remaining}</strong>
        </div>
      </div>

      <div class="event-meta">
        <div>
          <span>Event ID</span>
          <strong>${event.id}</strong>
        </div>
        <div style="grid-column: span 2;">
          <span>시작 시간</span>
          <strong>${normalizeDate(event.eventStartAt)}</strong>
        </div>
      </div>

      <button class="select-btn" data-select-event-id="${event.id}" ${disabled}>
        ${remaining > 0 ? "이 이벤트 신청하기" : "마감된 이벤트"}
      </button>
    </article>
  `;
}

function renderSelectedEvent() {
  const applyBtn = $("applyBtn");

  if (!selectedEvent) {
    $("selectedTitle").textContent = "이벤트를 선택해주세요";
    $("selectedDescription").textContent =
      "위 티켓팅 목록에서 신청할 이벤트를 선택하면 상세 정보가 표시됩니다.";
    $("selectedEventId").textContent = "-";
    $("selectedCapacity").textContent = "-";
    $("selectedApplied").textContent = "-";
    $("selectedRemaining").textContent = "-";
    applyBtn.disabled = true;
    applyBtn.textContent = "이벤트 선택 후 신청 가능";
    return;
  }

  const remaining = Math.max(
    selectedEvent.capacity - selectedEvent.appliedCount,
    0
  );

  $("selectedTitle").textContent = selectedEvent.title;
  $("selectedDescription").textContent =
    selectedEvent.description || "이벤트 설명이 없습니다.";
  $("selectedEventId").textContent = selectedEvent.id;
  $("selectedCapacity").textContent = selectedEvent.capacity;
  $("selectedApplied").textContent = selectedEvent.appliedCount;
  $("selectedRemaining").textContent = remaining;

  applyBtn.disabled = remaining <= 0;
  applyBtn.textContent = remaining > 0 ? "선착순 신청하기" : "마감된 이벤트입니다";
}

$("refreshBtn").addEventListener("click", refreshEvents);
$("eventRefreshBtn").addEventListener("click", refreshEvents);

$("applyForm").addEventListener("submit", async (event) => {
  event.preventDefault();

  if (!selectedEvent) {
    setResult("applyResult", "신청할 이벤트를 먼저 선택해주세요.", "error");
    return;
  }

  const payload = {
    applicantName: $("applicantName").value,
  };

  try {
    const data = await request(`/api/events/${selectedEvent.id}/apply`, {
      method: "POST",
      body: JSON.stringify(payload),
    });

    $("applicationId").value = data.id;

    setResult(
      "applyResult",
      `신청이 접수되었습니다. ${badge(data.status)}${jsonBlock(data)}`,
      "success"
    );

    await refreshEvents();
    await refreshStatus();
  } catch (error) {
    setResult("applyResult", error.message, "error");
  }
});

$("lookupForm").addEventListener("submit", async (event) => {
  event.preventDefault();

  const applicationId = $("applicationId").value;

  try {
    const data = await request(`/api/applications/${applicationId}`);

    setResult(
      "lookupResult",
      `현재 신청 상태: ${badge(data.status)}${jsonBlock(data)}`,
      "success"
    );

    await refreshStatus();
  } catch (error) {
    setResult("lookupResult", error.message, "error");
  }
});

refreshEvents();
refreshStatus();

setInterval(refreshStatus, 5000);