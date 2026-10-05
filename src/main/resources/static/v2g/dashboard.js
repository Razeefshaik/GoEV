"use strict";
// All numerical values come from the research API. Animation only interpolates positions.
let scenarios = [],
  run = null,
  busy = false,
  metrics = null,
  playing = false,
  playTimer = null;
let motion = !matchMedia("(prefers-reduced-motion: reduce)").matches,
  map = null,
  mapLayer = null,
  mapVehicles = [],
  geo = new Map(),
  coords = new Map(),
  selectedEntity = null,
  animationFrame = null;
const el = (id) => document.getElementById(id),
  n = (x, d = 2) =>
    Number(x ?? 0).toLocaleString("en-US", {
      minimumFractionDigits: d,
      maximumFractionDigits: d,
    }),
  clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v)),
  esc = (s) =>
    String(s ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    ),
  C = {
    teal: "#009c83",
    blue: "#4b82ef",
    grey: "#93a4af",
    amber: "#e6a64b",
    red: "#df6475",
    ink: "#142d38",
  },
  NS = "http://www.w3.org/2000/svg",
  policyLabel = {
    coordinated: "Coordinated heuristic",
    nearest: "Nearest eligible",
    none: "Without V2G",
  },
  terminal = (s) =>
    [
      "COMPLETED",
      "CANCELLED",
      "FAILED",
      "EXPIRED",
      "CURTAILED",
      "REJECTED",
    ].includes(s);
async function api(path, body) {
  const response = await fetch(
    "/api/v2g" + path,
    body === undefined
      ? {}
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
  );
  let value;
  try {
    value = await response.json();
  } catch {
    throw Error("The research server did not return a valid response.");
  }
  if (!response.ok) throw Error(value.error || "Request failed");
  return value;
}
function svg(parent, name, attrs = {}, text) {
  const node = document.createElementNS(NS, name);
  Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
  if (text !== undefined) node.textContent = text;
  parent.append(node);
  return node;
}
function table(id, head, rows) {
  const node = el(id);
  node.replaceChildren();
  const tr = document.createElement("tr");
  head.forEach((t) => {
    const th = document.createElement("th");
    th.textContent = t;
    tr.append(th);
  });
  node.append(tr);
  rows.forEach((row) => {
    const tr = document.createElement("tr");
    row.forEach((t) => {
      const td = document.createElement("td");
      if (t instanceof Node) td.append(t);
      else td.textContent = t;
      tr.append(td);
    });
    node.append(tr);
  });
}
function actionable(node, label, fn) {
  node.setAttribute("tabindex", "0");
  node.setAttribute("role", "button");
  node.setAttribute("aria-label", label);
  node.addEventListener("click", fn);
  node.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      fn();
    }
  });
}
function tooltip(node, text) {
  svg(node, "title", {}, text);
  node.addEventListener("pointerenter", (e) =>
    showTip(text, e.clientX, e.clientY),
  );
  node.addEventListener("pointermove", (e) =>
    showTip(text, e.clientX, e.clientY),
  );
  node.addEventListener("pointerleave", hideTip);
  node.addEventListener("focus", () => {
    const r = node.getBoundingClientRect();
    showTip(text, r.x + r.width / 2, r.y);
  });
  node.addEventListener("blur", hideTip);
}
function showTip(text, x, y) {
  const tip = el("chartTooltip");
  tip.textContent = text;
  tip.hidden = false;
  tip.style.left =
    clamp(x + 14, 8, window.innerWidth - tip.offsetWidth - 8) + "px";
  tip.style.top =
    clamp(y + 12, 8, window.innerHeight - tip.offsetHeight - 8) + "px";
}
function hideTip() {
  el("chartTooltip").hidden = true;
}
function selected() {
  return scenarios.find((s) => s.id === el("scenario").value);
}
function fillConfig() {
  el("config").value = JSON.stringify(selected(), null, 2);
  clearPolicyComparison();
}
function setControls() {
  for (const id of [
    "start",
    "scenario",
    "policy",
    "hours",
    "heartbeat",
    "compare",
  ])
    el(id).disabled = busy;
  for (const id of ["advance", "finish", "play"])
    el(id).disabled =
      busy || !run || run.hour >= run.scenario.settings.horizonHours - 1e-8;
  el("export").disabled = !run || busy;
  if (playing) el("play").disabled = false;
  el("activity").textContent = busy
    ? "Calculating electrical and battery state…"
    : playing
      ? "Playing · accelerated simulated time"
      : run
        ? "Saved locally · " + run.id.slice(0, 8)
        : "Choose a scenario and start exploring.";
}
async function act(fn) {
  if (busy) return;
  busy = true;
  el("error").hidden = true;
  setControls();
  try {
    await fn();
  } catch (e) {
    stopPlay();
    el("error").textContent = e.message;
    el("error").hidden = false;
  } finally {
    busy = false;
    setControls();
  }
}
function stopPlay() {
  playing = false;
  clearTimeout(playTimer);
  el("play").textContent = "▶ Play simulation";
  setControls();
}
async function tick() {
  if (!playing) return;
  await act(() => advance(Number(el("hours").value)));
  if (playing && run.hour < run.scenario.settings.horizonHours - 1e-8)
    playTimer = setTimeout(tick, 1400);
  else stopPlay();
}
async function advance(hours) {
  const previous = run.hour;
  run = await api(`/runs/${run.id}/advance`, {
    operationId: crypto.randomUUID(),
    hours,
    simulatedHeartbeat: el("heartbeat").checked,
  });
  await render(previous);
}
function statusName(status) {
  return (
    {
      EN_ROUTE: "TRAVELLING",
      DISCHARGING: "SUPPLYING",
      COMPLETED: "COMPLETED",
      CURTAILED: "STOPPED",
    }[status] || status
  );
}
function stat(label, value) {
  return `<div><span>${esc(label)}</span><b>${esc(value)}</b></div>`;
}
function cards() {
  const d = run.currentV2g,
    f = d.flow,
    inject = f.buses.reduce((a, b) => a + b.injectionKw, 0);
  const values = [
    [
      "Demand supplied",
      d.servedKw,
      "kW",
      "Useful power reaching consumers",
      "↗",
      "accent",
    ],
    [
      "Supply gap",
      d.unservedKw,
      "kW",
      "Demand still left unserved",
      "◔",
      "gap",
    ],
    ["Main grid", f.sourceKw, "kW", "Power imported from the source", "⌁", ""],
    [
      "EV contribution",
      inject,
      "kW",
      "Arrived vehicles supplying now",
      "↯",
      "accent",
    ],
    [
      "Feeder losses",
      f.lossesKw,
      "kW",
      "Power lost along the branches",
      "≈",
      "",
    ],
    [
      "Lowest voltage",
      f.minVoltagePu,
      "pu",
      "Allowed minimum: " + n(run.scenario.settings.minPu, 2) + " pu",
      "∿",
      "",
    ],
  ];
  el("cards").innerHTML = values
    .map(
      ([label, val, unit, hint, icon, klass]) =>
        `<div class="metric ${klass}"><div class="metric-label">${label}<span class="metric-icon">${icon}</span></div><b>${n(val, unit === "pu" ? 3 : 1)} <small>${unit}</small></b><p>${hint}</p></div>`,
    )
    .join("");
}
function story() {
  const a = metrics.withoutV2g,
    b = metrics.withV2g,
    extra = b.servedKwh - a.servedKwh;
  el("benefit").textContent = (extra >= 0 ? "+" : "") + n(extra, 2) + " kWh";
  el("storyTitle").textContent =
    extra > 0.0001
      ? `EV support has supplied ${n(extra, 2)} kWh more neighbourhood demand.`
      : run.hour === 0
        ? run.sessions.length
          ? "The EVs are assigned. Their contribution starts after arrival."
          : "Ready to measure service. No EV support session is selected."
        : b.requestedKwh === b.servedKwh
          ? "All requested demand is supplied in this interval."
          : "The supply gap remains. EV support has not increased service in this interval.";
  el("storyText").textContent =
    `Same inputs, same electrical limits. ${n(b.unservedKwh)} kWh remains unserved over ${n(run.hour * 60, 1)} simulated minutes. ${run.policy === "none" ? "This run deliberately disables EV support." : "The benefit measures extra consumer energy, not just energy leaving the batteries."}`;
  el("energyExplain").textContent =
    `Demand requested: ${n(b.requestedKwh)} kWh. Your selected policy supplied ${n(b.servedKwh)} kWh; the baseline supplied ${n(a.servedKwh)} kWh. The remainder is explicitly unserved.`;
  const delta = b.lossKwh - a.lossKwh;
  el("lossExplain").textContent =
    `Selected policy: ${n(b.lossKwh)} kWh feeder losses (${delta >= 0 ? "+" : ""}${n(delta)} kWh versus baseline), while supplying ${n(extra)} kWh more demand. Absolute losses and useful service must be interpreted together.`;
  el("batteryExplain").textContent =
    `${n(b.batteryKwh)} kWh left EV batteries for discharge; ${n(b.deliveredKwh)} kWh reached the grid. Planned full travel budget: ${n(b.travelKwh)} kWh, including journeys still in progress.`;
}
function fleet() {
  el("fleetCount").textContent =
    `${run.sessions.filter((s) => s.status === "DISCHARGING").length} supplying · ${run.sessions.filter((s) => s.status === "EN_ROUTE").length} travelling · ${run.scenario.fleet.length} EVs`;
  el("fleetCards").replaceChildren();
  for (const ev of run.scenario.fleet) {
    const s = run.sessions.find((s) => s.evId === ev.id),
      soc = s ? s.currentSoc : ev.soc,
      state = s ? s.status : "AVAILABLE",
      progress = s ? clamp(run.hour / Math.max(s.etaHour, 1e-9), 0, 1) : 0;
    const card = document.createElement("article");
    card.className = "ev-card";
    card.innerHTML = `<div class="ev-head"><strong><span class="ev-symbol">▰</span>${esc(ev.id)}</strong><span class="status ${state}">${statusName(state)}</span></div><p class="ev-route">${s ? `Bus ${ev.bus} → ${esc(s.stationId)} · bus ${s.bus}` : `At bus ${ev.bus} · ${ev.willing ? "Owner willing" : "Owner declined support"}`}</p><div class="battery-head"><span>Battery charge (SOC)</span><b>${n(soc, 1)}%</b></div><div class="battery-track"><div class="battery-fill" style="width:${clamp(soc, 0, 100)}%"></div><div class="reserve-marker" style="left:${ev.reserveSoc}%"></div></div><div class="reserve-note">Owner reserve ${n(ev.reserveSoc, 0)}% · onward travel protected</div><div class="ev-stats">${stat("Supplying now", n(s?.actualKw, 1) + " kW")}${stat("Grid energy delivered", n(s?.deliveredKwh) + " kWh")}${stat("Remaining grid budget", n(s ? Math.max(0, s.deliverableGridKwh - s.deliveredKwh) : 0) + " kWh")}${stat("Support remaining", s ? n(s.remainingHours * 60, 1) + " min" : "Not assigned")}</div><div class="journey-track"><div style="width:${progress * 100}%"></div></div><p class="ev-message">${s ? (s.status === "EN_ROUTE" ? `Arrival in ${n(Math.max(0, s.etaHour - run.hour) * 60, 1)} min · ${n(s.distanceKm)} km estimated journey` : terminal(s.status) ? `Session ended · ${esc(s.reason || statusName(s.status))}` : `Arrived after ${n(s.etaHour * 60, 1)} min · estimated final SOC ${n(s.expectedFinalSoc, 1)}%`) : "No support session selected. Eligibility and network need determine admission."}</p>`;
    actionable(card, `Inspect ${ev.id}`, () => inspect("ev", ev.id));
    el("fleetCards").append(card);
  }
}
function engineering() {
  const f = run.currentV2g.flow,
    base = run.currentBaseline.flow;
  table(
    "sessions",
    [
      "EV → station / bus",
      "Status",
      "Assigned / actual kW",
      "Travel km / ETA min",
      "Arrival / current / reserve / final SOC %",
      "Initial battery / grid budget kWh",
      "Grid remaining kWh",
      "Delivered kWh",
      "Max / scheduled / remaining min",
      "Start / finish min",
      "Action",
    ],
    run.sessions.map((s) => {
      const tag = document.createElement("span");
      tag.className = "status " + s.status;
      tag.textContent = statusName(s.status);
      const button = document.createElement("button");
      button.className = "ghost small";
      button.textContent = "Cancel";
      button.disabled = terminal(s.status);
      button.onclick = () => {
        stopPlay();
        act(async () => {
          run = await api(`/runs/${run.id}/cancel`, {
            operationId: crypto.randomUUID(),
            sessionId: s.id,
          });
          await render();
        });
      };
      return [
        `${s.evId} → ${s.stationId} / ${s.bus}`,
        tag,
        `${n(s.assignedKw, 1)} / ${n(s.actualKw, 1)}`,
        `${n(s.distanceKm)} / ${n(s.etaHour * 60, 1)}`,
        `${n(s.arrivalSoc, 1)} / ${n(s.currentSoc, 1)} / ${n(s.reserveSoc, 1)} / ${n(s.expectedFinalSoc, 1)}`,
        `${n(s.availableBatteryKwh)} / ${n(s.deliverableGridKwh)}`,
        n(Math.max(0, s.deliverableGridKwh - s.deliveredKwh)),
        n(s.deliveredKwh),
        `${n(s.maxDurationHours * 60, 1)} / ${n(s.scheduledDurationHours * 60, 1)} / ${n(s.remainingHours * 60, 1)}`,
        `${n(s.startHour * 60, 1)} / ${n(s.endHour * 60, 1)}`,
        button,
      ];
    }),
  );
  busTable();
  table(
    "lines",
    [
      "Line",
      "From → to",
      "Direction",
      "P kW",
      "Q kvar",
      "Current A",
      "Loading %",
      "Loss kW",
      "Limit",
    ],
    f.lines.map((b) => [
      b.id,
      `${b.from} → ${b.to}`,
      b.direction,
      n(b.pKw),
      n(b.qKvar),
      n(b.currentA),
      n(b.loadingPercent),
      n(b.lossKw, 3),
      b.violation ? "VIOLATION" : "OK",
    ]),
  );
  table(
    "metrics",
    [
      "Case",
      "Requested kWh",
      "Served kWh",
      "Unserved kWh",
      "Loss kWh",
      "EV grid kWh",
      "Discharge battery kWh",
      "Planned travel kWh",
      "Shortage min",
    ],
    [
      ["Without EV support", metrics.withoutV2g],
      ["Selected policy", metrics.withV2g],
    ].map(([label, m]) => [
      label,
      n(m.requestedKwh, 3),
      n(m.servedKwh, 3),
      n(m.unservedKwh, 3),
      n(m.lossKwh, 3),
      n(m.deliveredKwh, 3),
      n(m.batteryKwh, 3),
      n(m.travelKwh, 3),
      n(m.shortageHours * 60, 1),
    ]),
  );
  el("rejections").replaceChildren();
  for (const reason of run.rejections) {
    const li = document.createElement("li");
    li.textContent = reason;
    el("rejections").append(li);
  }
  if (!run.rejections.length) {
    const li = document.createElement("li");
    li.textContent = "No pair rejections recorded in this run.";
    el("rejections").append(li);
  }
  const ack = run.outbox.filter((c) => c.acknowledged).length;
  el("outbox").textContent =
    `${run.outbox.length} persisted commands · ${ack} device acknowledgements. ${ack === 0 ? "This run has no device-confirmed execution; dashboard values remain simulation results." : "Acknowledgements come from the optional station communication layer."}`;
  const sc = run.scenario,
    cfg = sc.settings;
  el("assumptions").textContent =
    `${sc.conductor}; resistance coefficient ${sc.resistanceOhmKm} Ω/km. Nominal voltage ${cfg.voltageKv} kV, PF ${cfg.pf}, source cap ${cfg.sourceCapKw} kW, discharge efficiency ${cfg.efficiency}. ${sc.travelModel}. Solver residual ${f.residualPu.toExponential(2)} pu; power-balance error ${f.balanceResidualKw.toExponential(2)} kW. Dataset SHA-256: ${sc.datasetSha256}`;
  const full = run.fullDemandBaseline;
  el("baselineWarning").textContent =
    `If all requested demand were supplied without restrictions: ${full.voltageViolations} voltage violations, ${full.loadingViolations} branch-loading violations, minimum ${n(full.minVoltagePu, 3)} pu, source demand ${n(full.sourceKw, 1)} kW. ${full.sourceKw > cfg.sourceCapKw ? "Source cap would be exceeded. " : ""}The displayed operating cases shed demand to enforce limits; “OK” does not mean all demand is served.`;
  el("solverBadge").textContent = f.converged
    ? "Solver converged · balance checked"
    : "Solver did not converge";
  el("networkSummary").textContent =
    `${f.buses.length} buses · ${f.lines.length} branches · ${sc.stations.length} stations · ${f.voltageViolations + f.loadingViolations} current violations`;
}
function busTable() {
  if (!run) return;
  const query = el("busSearch").value.trim();
  table(
    "buses",
    [
      "Bus",
      "Without EV pu",
      "Selected pu",
      "Requested kW",
      "Served kW",
      "Unserved kW",
      "Q kvar",
      "EV kW",
      "Limit",
    ],
    run.currentV2g.flow.buses
      .filter((b) => !query || String(b.bus).includes(query))
      .map((b) => {
        const base = run.currentBaseline.flow.buses.find(
          (a) => a.bus === b.bus,
        );
        return [
          b.bus,
          n(base.voltagePu, 4),
          n(b.voltagePu, 4),
          n(b.requestedKw, 1),
          n(b.servedKw, 1),
          n(b.requestedKw - b.servedKw, 1),
          n(b.reactiveKvar, 1),
          n(b.injectionKw, 1),
          b.violation ? "VIOLATION" : "OK",
        ];
      }),
  );
}
// Native SVG charts remain usable offline; accessible points expose exact values.
function canvas(id) {
  const node = el(id);
  node.replaceChildren();
  node.setAttribute("viewBox", "0 0 560 225");
  return node;
}
function bars(id, rows, unit, maxOverride) {
  const node = canvas(id),
    max = Math.max(maxOverride || 0, ...rows.map((r) => r.value), 1e-6),
    width = 345;
  rows.forEach((r, i) => {
    const y = 20 + i * (170 / Math.max(rows.length, 1));
    svg(node, "text", { x: 0, y: y + 19 }, r.label);
    svg(node, "rect", { x: 135, y, width, height: 27, rx: 4, fill: "#f1f5f6" });
    const bar = svg(node, "rect", {
      x: 135,
      y,
      width: Math.max(0, (r.value / max) * width),
      height: 27,
      rx: 4,
      fill: r.color,
    });
    bar.setAttribute("tabindex", "0");
    tooltip(bar, `${r.label}: ${n(r.value, 3)} ${unit}`);
    svg(node, "text", { x: 490, y: y + 19, class: "value" }, n(r.value, 2));
  });
  svg(node, "text", { x: 135, y: 216 }, `Same scale · ${unit}`);
}
function energyChart() {
  const node = canvas("energyChart"),
    a = metrics.withoutV2g,
    b = metrics.withV2g,
    max = Math.max(a.requestedKwh, b.requestedKwh, 1e-8);
  if (b.requestedKwh === 0) {
    svg(
      node,
      "text",
      { x: 280, y: 110, "text-anchor": "middle" },
      "Advance time to accumulate demand and supplied energy.",
    );
    return;
  }
  [
    ["Without EVs", a, C.grey],
    ["Selected policy", b, C.teal],
  ].forEach(([label, m, color], i) => {
    const y = 35 + i * 70;
    svg(node, "text", { x: 5, y: y + 17 }, label);
    svg(node, "rect", {
      x: 120,
      y,
      width: 420,
      height: 34,
      rx: 5,
      fill: "#fff0dc",
    });
    const bar = svg(node, "rect", {
      x: 120,
      y,
      width: (420 * m.servedKwh) / max,
      height: 34,
      rx: 5,
      fill: color,
      tabindex: 0,
    });
    tooltip(
      bar,
      `${label}: ${n(m.servedKwh)} kWh supplied; ${n(m.unservedKwh)} kWh unserved`,
    );
    svg(
      node,
      "text",
      { x: 120, y: y + 50, class: "value" },
      `${n(m.servedKwh)} supplied · ${n(m.unservedKwh)} unserved`,
    );
  });
  svg(node, "rect", {
    x: 120,
    y: 200,
    width: 8,
    height: 8,
    fill: C.teal,
    rx: 2,
  });
  svg(node, "text", { x: 135, y: 208 }, "Supplied");
  svg(node, "rect", {
    x: 230,
    y: 200,
    width: 8,
    height: 8,
    fill: "#f2cc89",
    rx: 2,
  });
  svg(node, "text", { x: 245, y: 208 }, "Unserved");
}
function lineChart(
  id,
  series,
  {
    min = 0,
    max = null,
    xUnit = "",
    unit = "",
    threshold = null,
    steps = false,
  } = {},
) {
  const node = canvas(id),
    all = series.flatMap((s) => s.points);
  if (!all.length) {
    svg(
      node,
      "text",
      { x: 280, y: 105, "text-anchor": "middle" },
      "Advance simulated time to reveal the curve.",
    );
    return;
  }
  const yMax = max ?? Math.max(...all.map((p) => p.y), min + 1),
    lo = Math.min(min, ...all.map((p) => p.y)),
    hi = Math.max(yMax, lo + 0.001),
    xMin = Math.min(...all.map((p) => p.x)),
    xMax = Math.max(...all.map((p) => p.x), xMin + 1),
    x = (v) => 48 + ((v - xMin) / (xMax - xMin)) * 486,
    y = (v) => 178 - ((v - lo) / (hi - lo)) * 137;
  for (let i = 0; i < 4; i++) {
    const val = lo + ((hi - lo) * i) / 3;
    svg(node, "line", {
      x1: 48,
      x2: 534,
      y1: y(val),
      y2: y(val),
      stroke: "#e8eef0",
    });
    svg(
      node,
      "text",
      { x: 39, y: y(val) + 3, "text-anchor": "end" },
      n(val, hi <= 2 ? 2 : 0),
    );
  }
  for (let i = 0; i < 5; i++) {
    const val = xMin + ((xMax - xMin) * i) / 4;
    svg(
      node,
      "text",
      { x: x(val), y: 198, "text-anchor": "middle" },
      n(val, 0),
    );
  }
  if (threshold !== null) {
    svg(node, "line", {
      x1: 48,
      x2: 534,
      y1: y(threshold),
      y2: y(threshold),
      stroke: C.amber,
      "stroke-dasharray": "5 4",
    });
    svg(
      node,
      "text",
      { x: 532, y: y(threshold) - 6, "text-anchor": "end", fill: C.amber },
      "Allowed limit",
    );
  }
  series.forEach((s, index) => {
    const points = s.points;
    let d = "";
    points.forEach((p, i) => {
      if (i === 0) d = `M ${x(p.x)} ${y(p.y)}`;
      else if (steps) d += ` H ${x(p.x)} V ${y(p.y)}`;
      else d += ` L ${x(p.x)} ${y(p.y)}`;
    });
    svg(node, "path", {
      d,
      fill: "none",
      stroke: s.color,
      "stroke-width": 2.5,
      "stroke-linejoin": "round",
    });
    for (const p of points) {
      const dot = svg(node, "circle", {
        cx: x(p.x),
        cy: y(p.y),
        r: 4,
        fill: s.color,
        opacity: 0.7,
        tabindex: 0,
        class: "chart-point",
      });
      tooltip(dot, `${s.label} · ${xUnit} ${n(p.x, 1)}: ${n(p.y, 3)} ${unit}`);
    }
    svg(node, "rect", {
      x: 48 + index * 175,
      y: 10,
      width: 8,
      height: 8,
      fill: s.color,
      rx: 2,
    });
    svg(node, "text", { x: 62 + index * 175, y: 18 }, s.label);
  });
  svg(node, "text", { x: 534, y: 218, "text-anchor": "end" }, xUnit);
}
function charts() {
  energyChart();
  const f = run.currentV2g.flow,
    base = run.currentBaseline.flow,
    ev = f.buses.reduce((a, b) => a + b.injectionKw, 0);
  bars(
    "balanceChart",
    [
      { label: "Main grid", value: f.sourceKw, color: C.grey },
      { label: "EV power", value: ev, color: C.teal },
      { label: "To consumers", value: run.currentV2g.servedKw, color: C.blue },
      { label: "Feeder losses", value: f.lossesKw, color: C.amber },
    ],
    "kW",
  );
  const series = [
      { label: "Without EVs", color: C.grey, points: [] },
      { label: "Selected policy", color: C.teal, points: [] },
    ],
    gap = structuredClone(series);
  let a = 0,
    b = 0;
  gap[0].points.push({ x: 0, y: 0 });
  gap[1].points.push({ x: 0, y: 0 });
  for (const s of run.samples) {
    const start = s.hour * 60,
      end = (s.hour + s.durationHours) * 60;
    series[0].points.push(
      { x: start, y: s.baseline.servedKw },
      { x: end, y: s.baseline.servedKw },
    );
    series[1].points.push(
      { x: start, y: s.v2g.servedKw },
      { x: end, y: s.v2g.servedKw },
    );
    a += s.baseline.unservedKw * s.durationHours;
    b += s.v2g.unservedKw * s.durationHours;
    gap[0].points.push({ x: end, y: a });
    gap[1].points.push({ x: end, y: b });
  }
  lineChart("supplyChart", series, {
    unit: "kW",
    xUnit: "Minute",
    steps: true,
  });
  lineChart("gapChart", gap, { unit: "kWh", xUnit: "Minute" });
  lineChart(
    "voltage",
    [
      {
        label: "Without EVs",
        color: C.grey,
        points: base.buses.map((b) => ({ x: b.bus, y: b.voltagePu })),
      },
      {
        label: "Selected policy",
        color: C.teal,
        points: f.buses.map((b) => ({ x: b.bus, y: b.voltagePu })),
      },
    ],
    {
      min: Math.min(
        run.scenario.settings.minPu - 0.015,
        ...f.buses.map((b) => b.voltagePu),
      ),
      max: 1.01,
      threshold: run.scenario.settings.minPu,
      unit: "pu",
      xUnit: "Bus",
    },
  );
  lineChart(
    "loadingChart",
    [
      {
        label: "Without EVs",
        color: C.grey,
        points: [...base.lines]
          .sort((a, b) => a.id - b.id)
          .map((b) => ({ x: b.id, y: b.loadingPercent })),
      },
      {
        label: "Selected policy",
        color: C.teal,
        points: [...f.lines]
          .sort((a, b) => a.id - b.id)
          .map((b) => ({ x: b.id, y: b.loadingPercent })),
      },
    ],
    {
      max: Math.max(110, f.maxLoadingPercent + 5, base.maxLoadingPercent + 5),
      threshold: 100,
      unit: "%",
      xUnit: "Branch",
    },
  );
  bars(
    "batteryChart",
    [
      {
        label: "Battery discharge",
        value: metrics.withV2g.batteryKwh,
        color: C.blue,
      },
      {
        label: "Grid delivered",
        value: metrics.withV2g.deliveredKwh,
        color: C.teal,
      },
      {
        label: "Conversion loss",
        value: Math.max(
          0,
          metrics.withV2g.batteryKwh - metrics.withV2g.deliveredKwh,
        ),
        color: C.amber,
      },
      {
        label: "Planned travel",
        value: metrics.withV2g.travelKwh,
        color: C.grey,
      },
    ],
    "kWh",
  );
  bars(
    "lossChart",
    [
      {
        label: "Without EVs",
        value: metrics.withoutV2g.lossKwh,
        color: C.grey,
      },
      {
        label: "Selected policy",
        value: metrics.withV2g.lossKwh,
        color: C.teal,
      },
    ],
    "kWh",
  );
}
function route(from, to) {
  const adjacency = new Map();
  for (const b of run.scenario.branches) {
    for (const [a, z] of [
      [b.from, b.to],
      [b.to, b.from],
    ]) {
      if (!adjacency.has(a)) adjacency.set(a, []);
      adjacency.get(a).push({ bus: z, length: b.lengthKm });
    }
  }
  const queue = [{ bus: from, path: [from], lengths: [] }],
    seen = new Set();
  while (queue.length) {
    const p = queue.shift();
    if (p.bus === to) return p;
    seen.add(p.bus);
    for (const edge of adjacency.get(p.bus) || [])
      if (!seen.has(edge.bus))
        queue.push({
          bus: edge.bus,
          path: [...p.path, edge.bus],
          lengths: [...p.lengths, edge.length],
        });
  }
  return { path: [from], lengths: [] };
}
function travelFraction(session, hour) {
  const stop = terminal(session.status)
    ? Math.min(hour, session.actualStopHour)
    : hour;
  return session.etaHour ? clamp(stop / session.etaHour, 0, 1) : 1;
}
function along(path, fraction, positions) {
  const sum = path.lengths.reduce((a, b) => a + b, 0);
  let left = clamp(fraction, 0, 1) * sum;
  if (!sum) return positions.get(path.path[0]);
  for (let i = 0; i < path.lengths.length; i++) {
    const length = path.lengths[i];
    if (left <= length || i === path.lengths.length - 1) {
      const a = positions.get(path.path[i]),
        b = positions.get(path.path[i + 1]),
        t = length ? clamp(left / length, 0, 1) : 1;
      return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
    }
    left -= length;
  }
  return positions.get(path.path.at(-1));
}
function busColor(b) {
  if (b.violation) return C.red;
  const mode = el("networkMetric").value;
  if (mode === "injection") return b.injectionKw > 0.01 ? C.teal : "#c4d3db";
  if (mode === "unserved") {
    const ratio = b.requestedKw ? 1 - b.servedKw / b.requestedKw : 0;
    return ratio > 0.5 ? "#e1a54d" : ratio > 0.01 ? "#c7be78" : C.teal;
  }
  return b.voltagePu < run.scenario.settings.minPu + 0.005
    ? "#e1ad57"
    : b.voltagePu < 0.975
      ? "#65b4a4"
      : "#539cbd";
}
function freshStation(st) {
  return (
    st.online &&
    st.telemetryHour <= run.hour + 1e-9 &&
    (el("heartbeat").checked ||
      run.hour - st.telemetryHour <=
        run.scenario.settings.freshnessHours + 1e-9)
  );
}
function layout() {
  coords = new Map();
  const children = new Map();
  for (const b of run.scenario.branches) {
    if (!children.has(b.from)) children.set(b.from, []);
    children.get(b.from).push(b.to);
  }
  let leaf = 0;
  function visit(bus, depth) {
    const list = children.get(bus) || [],
      y = list.length
        ? list.reduce((sum, child) => sum + visit(child, depth + 1), 0) /
          list.length
        : 65 + leaf++ * 62;
    coords.set(bus, [48 + depth * 79, y]);
    return y;
  }
  visit(1, 0);
}
function network(previousHour = run.hour) {
  if (animationFrame) cancelAnimationFrame(animationFrame);
  layout();
  const node = el("topology");
  node.replaceChildren();
  const defs = svg(node, "defs");
  const marker = svg(defs, "marker", {
    id: "flowArrow",
    viewBox: "0 0 10 10",
    refX: 8,
    refY: 5,
    markerWidth: 4,
    markerHeight: 4,
    orient: "auto-start-reverse",
  });
  svg(marker, "path", { d: "M 0 0 L 10 5 L 0 10 z", fill: "#7396a6" });
  for (const b of run.currentV2g.flow.lines) {
    const a = coords.get(b.from),
      z = coords.get(b.to),
      attrs = { x1: a[0], y1: a[1], x2: z[0], y2: z[1] };
    svg(node, "line", {
      ...attrs,
      class: "branch",
      stroke: b.violation ? C.red : "#b6c9d2",
      ...(b.pKw < 0
        ? { "marker-start": "url(#flowArrow)" }
        : { "marker-end": "url(#flowArrow)" }),
    });
    const flow = svg(node, "line", {
      ...attrs,
      class: "energy-flow",
      style: b.pKw < 0 ? "animation-direction:reverse" : "",
    });
    if (Math.abs(b.pKw) < 0.001) flow.style.display = "none";
    const hit = svg(node, "line", { ...attrs, class: "branch-hit" });
    actionable(hit, `Inspect branch ${b.id}, bus ${b.from} to ${b.to}`, () =>
      inspect("line", b.id),
    );
    tooltip(
      hit,
      `Branch ${b.id} · ${n(b.pKw)} kW · ${n(b.loadingPercent, 1)}% loading`,
    );
    svg(
      node,
      "text",
      { x: (a[0] + z[0]) / 2, y: (a[1] + z[1]) / 2 - 8, class: "line-label" },
      n(b.pKw, 0) + " kW",
    );
  }
  const moving = [];
  for (const s of run.sessions) {
    const ev = run.scenario.fleet.find((e) => e.id === s.evId),
      path = route(ev.bus, s.bus);
    if (selectedEntity?.type === "ev" && selectedEntity.id === ev.id)
      svg(node, "polyline", {
        points: path.path.map((bus) => coords.get(bus).join(",")).join(" "),
        class: "route-highlight",
      });
    moving.push({ s, ev, path });
  }
  for (const ev of run.scenario.fleet)
    if (!run.sessions.some((s) => s.evId === ev.id))
      moving.push({
        s: { status: "AVAILABLE", etaHour: 0 },
        ev,
        path: { path: [ev.bus], lengths: [] },
      });
  for (const b of run.currentV2g.flow.buses) {
    const c = coords.get(b.bus),
      g = svg(node, "g", { class: "node", "data-bus": b.bus });
    svg(g, "circle", {
      cx: c[0],
      cy: c[1],
      r: 17,
      fill: "#fff",
      stroke: busColor(b),
      "stroke-width": 2,
      class: "bus-ring",
    });
    svg(g, "circle", {
      cx: c[0],
      cy: c[1],
      r: 12,
      fill: busColor(b),
      opacity: 0.13,
    });
    svg(
      g,
      "text",
      { x: c[0], y: c[1] + 4, "text-anchor": "middle", class: "bus-label" },
      b.bus,
    );
    svg(
      g,
      "text",
      { x: c[0], y: c[1] + 32, "text-anchor": "middle", class: "bus-voltage" },
      n(b.voltagePu, 3) + " pu",
    );
    actionable(g, `Inspect bus ${b.bus}, voltage ${n(b.voltagePu, 3)} pu`, () =>
      inspect("bus", b.bus),
    );
    tooltip(
      g,
      `Bus ${b.bus} · ${n(b.voltagePu, 3)} pu · ${n(b.servedKw, 1)} kW supplied`,
    );
  }
  for (const st of run.scenario.stations) {
    const c = coords.get(st.bus),
      g = svg(node, "g", { class: "node", "data-station": st.id });
    svg(g, "rect", {
      x: c[0] - 10,
      y: c[1] - 46,
      width: 20,
      height: 22,
      rx: 4,
      fill: freshStation(st) ? C.amber : C.grey,
    });
    svg(
      g,
      "text",
      {
        x: c[0],
        y: c[1] - 31,
        "text-anchor": "middle",
        fill: "white",
        "font-size": 15,
      },
      "↯",
    );
    svg(
      g,
      "text",
      {
        x: c[0],
        y: c[1] - 53,
        "text-anchor": "middle",
        class: "station-label",
      },
      st.id,
    );
    actionable(g, `Inspect station ${st.id}`, () => inspect("station", st.id));
  }
  const evNodes = moving.map(({ s, ev, path }, index) => {
    const g = svg(node, "g", { "data-ev": ev.id, class: "ev-icon" }),
      color =
        s.status === "DISCHARGING"
          ? C.teal
          : s.status === "EN_ROUTE"
            ? C.blue
            : C.grey;
    const ring = svg(g, "circle", {
      cx: 0,
      cy: 0,
      r: 16,
      fill: color,
      opacity: 0.2,
      class: s.status === "DISCHARGING" ? "pulse" : "",
    });
    svg(g, "rect", {
      x: -13,
      y: -7,
      width: 26,
      height: 14,
      rx: 5,
      fill: color,
      stroke: "white",
      "stroke-width": 2,
    });
    svg(g, "rect", {
      x: -5,
      y: -5,
      width: 10,
      height: 5,
      rx: 1,
      fill: "white",
      opacity: 0.75,
    });
    svg(g, "circle", { cx: -7, cy: 7, r: 2.5, fill: C.ink });
    svg(g, "circle", { cx: 7, cy: 7, r: 2.5, fill: C.ink });
    svg(g, "text", { x: 17, y: 4, class: "ev-label" }, ev.id);
    actionable(g, `Inspect ${ev.id}, ${statusName(s.status)}`, () =>
      inspect("ev", ev.id),
    );
    return { g, s, path, index };
  });
  if (map && !el("map").hidden) renderMap();
  const started = performance.now(),
    duration = motion && previousHour !== run.hour ? 1200 : 0;
  function draw(now) {
    const t = duration ? clamp((now - started) / duration, 0, 1) : 1,
      h = previousHour + (run.hour - previousHour) * t;
    for (const { g, s, path, index } of evNodes) {
      const at = along(path, travelFraction(s, h), coords);
      g.setAttribute(
        "transform",
        `translate(${at[0] + (s.status === "EN_ROUTE" ? 0 : 22)} ${at[1] + (s.status === "EN_ROUTE" ? -12 : 14 + (index % 2) * 19)})`,
      );
    }
    for (const { marker, s, path } of mapVehicles) {
      const at = along(path, travelFraction(s, h), geo);
      if (at) marker.setLatLng(at);
    }
    if (t < 1) animationFrame = requestAnimationFrame(draw);
    else animationFrame = null;
  }
  draw(started);
  if (duration) animationFrame = requestAnimationFrame(draw);
}
function inspectorRows(rows) {
  return (
    "<dl>" +
    rows
      .map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`)
      .join("") +
    "</dl>"
  );
}
function inspect(type, id, highlight = true) {
  if (!run) return;
  selectedEntity = { type, id };
  let title,
    sub,
    rows = [],
    extra = "";
  const f = run.currentV2g.flow;
  if (type === "bus") {
    const b = f.buses.find((b) => b.bus === id),
      a = run.currentBaseline.flow.buses.find((b) => b.bus === id);
    title = "Bus " + id;
    sub =
      id === 1
        ? "Main grid source. All downstream demand starts here."
        : "An electrical connection point for local loads and feeder branches.";
    rows = [
      ["Voltage", n(b.voltagePu, 4) + " pu"],
      ["Without EV voltage", n(a.voltagePu, 4) + " pu"],
      ["Demand requested", n(b.requestedKw, 1) + " kW"],
      ["Demand supplied", n(b.servedKw, 1) + " kW"],
      ["Supply gap", n(b.requestedKw - b.servedKw, 1) + " kW"],
      ["EV injection", n(b.injectionKw, 1) + " kW"],
      ["Voltage limit", b.violation ? "Outside limit" : "Within limit"],
    ];
  } else if (type === "line") {
    const b = f.lines.find((b) => b.id === id);
    title = "Branch " + id;
    sub = `Bus ${b.from} → bus ${b.to}. ${b.direction === "REVERSE" ? "Power flows back toward the source." : "Power flows toward downstream consumers."}`;
    rows = [
      ["Real power", n(b.pKw) + " kW"],
      ["Reactive power", n(b.qKvar) + " kvar"],
      ["Current", n(b.currentA) + " A"],
      ["Loading", n(b.loadingPercent, 1) + "%"],
      ["Loss", n(b.lossKw, 3) + " kW"],
      ["Branch limit", b.violation ? "Outside limit" : "Within limit"],
    ];
  } else if (type === "station") {
    const st = run.scenario.stations.find((s) => s.id === id),
      sessions = run.sessions.filter(
        (s) => s.stationId === id && !terminal(s.status),
      );
    title = st.id;
    sub = `Bidirectional station connected to bus ${st.bus}. Ports and total site power constrain simultaneous support.`;
    rows = [
      [
        "Availability",
        freshStation(st) ? "Simulated online" : "Offline / stale",
      ],
      ["Physical ports", st.totalPorts],
      ["Session commitments", sessions.length],
      ["Per-port limit", n(st.portKw, 0) + " kW"],
      ["Site limit", n(st.siteKw, 0) + " kW"],
      [
        "Actual discharge",
        n(
          sessions.reduce((a, s) => a + s.actualKw, 0),
          1,
        ) + " kW",
      ],
    ];
  } else {
    const ev = run.scenario.fleet.find((e) => e.id === id),
      s = run.sessions.find((s) => s.evId === id);
    title = id;
    sub = s
      ? `Bus ${ev.bus} → ${s.stationId} at bus ${s.bus}. The highlighted path is the feeder-distance travel surrogate.`
      : "No support assignment selected in this experiment.";
    rows = [
      ["State", s ? statusName(s.status) : "Available"],
      ["Current SOC", n(s ? s.currentSoc : ev.soc, 1) + "%"],
      ["Owner reserve", n(ev.reserveSoc, 0) + "%"],
      ["Actual output", n(s?.actualKw, 1) + " kW"],
      ["Delivered", n(s?.deliveredKwh) + " kWh"],
      ["ETA from start", s ? n(s.etaHour * 60, 1) + " min" : "—"],
      ["Support remaining", s ? n(s.remainingHours * 60, 1) + " min" : "—"],
    ];
    if (s && !terminal(s.status))
      extra =
        '<button id="inspectCancel" class="ghost inspect-action">Cancel this session</button>';
  }
  el("inspector").innerHTML =
    `<span class="eyebrow">${esc(type.toUpperCase())} INSPECTOR</span><h3>${esc(title)}</h3><p>${esc(sub)}</p>${inspectorRows(rows)}${extra}<p class="muted">Calculated simulation state. Physical execution requires equipment telemetry.</p>`;
  if (el("inspectCancel"))
    el("inspectCancel").onclick = () => {
      const s = run.sessions.find((s) => s.evId === id);
      stopPlay();
      act(async () => {
        run = await api(`/runs/${run.id}/cancel`, {
          operationId: crypto.randomUUID(),
          sessionId: s.id,
        });
        await render();
      });
    };
  if (type === "ev" && highlight) network();
}
function renderMap() {
  if (!map || !run) return;
  mapLayer.clearLayers();
  mapVehicles = [];
  for (const b of run.currentV2g.flow.lines) {
    const a = geo.get(b.from),
      z = geo.get(b.to);
    if (!a || !z) continue;
    const line = L.polyline([a, z], {
      color: b.violation ? C.red : "#6da99b",
      weight: 3,
      className:
        Math.abs(b.pKw) > 0.001
          ? "map-feeder" + (b.pKw < 0 ? " reverse" : "")
          : "",
    }).addTo(mapLayer);
    line.bindTooltip(
      `Branch ${b.id}: ${n(b.pKw)} kW · ${n(b.loadingPercent, 1)}% loading`,
    );
    line.on("click", () => inspect("line", b.id));
  }
  for (const b of run.currentV2g.flow.buses) {
    const c = geo.get(b.bus);
    if (!c) continue;
    const marker = L.marker(c, {
      icon: L.divIcon({
        className: "map-bus",
        html: esc(b.bus),
        iconSize: [24, 24],
        iconAnchor: [12, 12],
      }),
      title: "Bus " + b.bus,
      keyboard: true,
    }).addTo(mapLayer);
    marker.getElement().style.background = busColor(b);
    marker.bindTooltip(`Bus ${b.bus}: ${n(b.voltagePu, 3)} pu`);
    marker.on("click", () => inspect("bus", b.bus));
  }
  for (const st of run.scenario.stations) {
    const c = geo.get(st.bus);
    if (!c) continue;
    const marker = L.marker([c[0] + 0.002, c[1]], {
      icon: L.divIcon({
        className: "map-station",
        html: "↯",
        iconSize: [24, 26],
        iconAnchor: [12, 26],
      }),
      title: st.id,
    }).addTo(mapLayer);
    if (!freshStation(st)) marker.getElement().style.background = C.grey;
    marker.bindTooltip(st.id);
    marker.on("click", () => inspect("station", st.id));
  }
  for (const ev of run.scenario.fleet) {
    const s = run.sessions.find((s) => s.evId === ev.id),
      path = s ? route(ev.bus, s.bus) : { path: [ev.bus], lengths: [] },
      at = along(path, s ? travelFraction(s, run.hour) : 0, geo);
    if (!at) continue;
    if (selectedEntity?.type === "ev" && selectedEntity.id === ev.id)
      L.polyline(
        path.path.map((b) => geo.get(b)),
        { color: C.blue, weight: 7, opacity: 0.4 },
      ).addTo(mapLayer);
    const marker = L.marker(at, {
      icon: L.divIcon({
        className: "map-ev" + (s?.status === "DISCHARGING" ? " active" : ""),
        html: "▰",
        iconSize: [27, 23],
        iconAnchor: [-5, 12],
      }),
      title: ev.id,
    }).addTo(mapLayer);
    if (!s || terminal(s.status)) marker.getElement().style.background = C.grey;
    marker.bindTooltip(`${ev.id} · ${s ? statusName(s.status) : "Available"}`);
    marker.on("click", () => inspect("ev", ev.id));
    if (s) mapVehicles.push({ marker, s, path });
  }
}
async function mapView() {
  el("map").parentElement.classList.add("map-view");
  el("schematicWrap").hidden = true;
  el("map").hidden = false;
  el("schematicTab").classList.remove("selected");
  el("mapTab").classList.add("selected");
  el("schematicTab").setAttribute("aria-pressed", "false");
  el("mapTab").setAttribute("aria-pressed", "true");
  el("stageLabel").textContent = "AMTALA ANCHOR · GENERATED BUS LOCATIONS";
  el("mapStatus").textContent =
    "Map background uses OpenStreetMap. Feeder lines and EV paths are generated, not surveyed roads.";
  if (!map) {
    if (!window.L) {
      el("mapStatus").textContent =
        "Map library unavailable. Electrical view remains available.";
      return;
    }
    try {
      const response = await fetch("/v2g/coordinates.json");
      if (!response.ok) throw Error("Map coordinates unavailable");
      const points = await response.json();
      geo = new Map(
        points.map((p) => [
          Number(p.bus_id),
          [Number(p.latitude), Number(p.longitude)],
        ]),
      );
      map = L.map("map", { scrollWheelZoom: true }).setView(
        [22.3669, 88.2777],
        11,
      );
      const tiles = L.tileLayer(
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        {
          maxZoom: 19,
          attribution:
            '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        },
      ).addTo(map);
      tiles.on("tileerror", () => {
        el("mapStatus").textContent =
          "Map background unavailable. Generated bus, feeder and EV overlays remain interactive.";
      });
      mapLayer = L.layerGroup().addTo(map);
      L.control.scale({ imperial: false }).addTo(map);
      map.fitBounds([...geo.values()], { padding: [35, 35] });
    } catch (e) {
      el("mapStatus").textContent = e.message;
      return;
    }
  }
  map.invalidateSize();
  renderMap();
}
function electricalView() {
  el("map").parentElement.classList.remove("map-view");
  el("schematicWrap").hidden = false;
  el("map").hidden = true;
  el("schematicTab").classList.add("selected");
  el("mapTab").classList.remove("selected");
  el("schematicTab").setAttribute("aria-pressed", "true");
  el("mapTab").setAttribute("aria-pressed", "false");
  el("stageLabel").textContent = "ELECTRICAL TOPOLOGY · LIVE SIMULATED STATE";
  el("mapStatus").textContent =
    "Vehicle motion interpolates calculated time steps. Arrows show real-power direction.";
}
function clearPolicyComparison() {
  window.policyResults = [];
  el("policyChart").replaceChildren();
  el("policyTable").replaceChildren();
  el("compareStatus").textContent =
    "This creates separate reproducible experiments. Your current run stays in place.";
}
async function comparePolicies() {
  stopPlay();
  const scenario = JSON.parse(el("config").value),
    results = [];
  clearPolicyComparison();
  for (const policy of ["none", "nearest", "coordinated"]) {
    el("compareStatus").textContent =
      `Calculating ${policyLabel[policy]} over ${n(scenario.settings.horizonHours, 1)} hours…`;
    let other = await api("/runs", {
      requestId: crypto.randomUUID(),
      scenario,
      policy,
    });
    other = await api(`/runs/${other.id}/advance`, {
      operationId: crypto.randomUUID(),
      hours: scenario.settings.horizonHours,
      simulatedHeartbeat: el("heartbeat").checked,
    });
    const result = await api(`/runs/${other.id}/metrics`);
    results.push({ policy, id: other.id, metric: result.withV2g });
  }
  bars(
    "policyChart",
    results.map((r, i) => ({
      label:
        i === 0 ? "Without EVs" : i === 1 ? "Nearest eligible" : "Coordinated",
      value: r.metric.servedKwh,
      color: [C.grey, C.blue, C.teal][i],
    })),
    "kWh supplied",
  );
  table(
    "policyTable",
    [
      "Policy",
      "Served kWh",
      "Unserved kWh",
      "EV grid kWh",
      "Loss kWh",
      "Minimum pu",
      "Experiment",
    ],
    results.map((r) => [
      policyLabel[r.policy],
      n(r.metric.servedKwh, 3),
      n(r.metric.unservedKwh, 3),
      n(r.metric.deliveredKwh, 3),
      n(r.metric.lossKwh, 3),
      n(r.metric.minVoltagePu, 4),
      r.id.slice(0, 8),
    ]),
  );
  const nearest = results[1].metric.servedKwh,
    coordinated = results[2].metric.servedKwh,
    delta = coordinated - nearest;
  el("compareStatus").textContent =
    `Full ${n(scenario.settings.horizonHours, 1)}-hour horizon · identical scenario inputs and heartbeat setting. ${Math.abs(delta) < 0.001 ? "Nearest and coordinated tie for served energy in this experiment." : `Coordinated supplies ${n(Math.abs(delta))} kWh ${delta > 0 ? "more" : "less"} than nearest in this experiment.`} No global optimality claim. Your live run is unchanged.`;
  window.policyResults = results;
}
async function render(previousHour = run.hour) {
  metrics = await api(`/runs/${run.id}/metrics`);
  el("clock").textContent =
    `${run.scenario.id} · ${n(run.hour, 3)} / ${n(run.scenario.settings.horizonHours, 1)} simulated hours`;
  el("clockMinutes").textContent =
    `${n(run.hour * 60, 1)} of ${n(run.scenario.settings.horizonHours * 60, 0)} min`;
  el("policyName").textContent = policyLabel[run.policy];
  el("timeProgress").style.width =
    clamp((run.hour / run.scenario.settings.horizonHours) * 100, 0, 100) + "%";
  el("comparisonWindow").textContent =
    `First ${n(run.hour * 60, 1)} simulated minutes`;
  cards();
  story();
  fleet();
  engineering();
  charts();
  network(previousHour);
  if (selectedEntity) inspect(selectedEntity.type, selectedEntity.id, false);
  setControls();
}
el("start").onclick = () => {
  stopPlay();
  act(async () => {
    const scenario = JSON.parse(el("config").value);
    run = await api("/runs", {
      requestId: crypto.randomUUID(),
      scenario,
      policy: el("policy").value,
    });
    selectedEntity = null;
    localStorage.setItem("goev-v2g-run", run.id);
    clearPolicyComparison();
    await render();
  });
};
el("advance").onclick = () => {
  stopPlay();
  act(() => advance(Number(el("hours").value)));
};
el("finish").onclick = () => {
  stopPlay();
  act(() =>
    advance(Math.max(0, run.scenario.settings.horizonHours - run.hour)),
  );
};
el("play").onclick = () => {
  if (playing) stopPlay();
  else {
    playing = true;
    el("play").textContent = "Ⅱ Pause";
    tick();
  }
};
el("compare").onclick = () => act(comparePolicies);
el("scenario").onchange = fillConfig;
el("config").oninput = clearPolicyComparison;
el("policy").onchange = clearPolicyComparison;
el("heartbeat").onchange = () => {
  if (run) {
    network();
    if (selectedEntity) inspect(selectedEntity.type, selectedEntity.id);
  }
};
el("networkMetric").onchange = () => {
  if (run) network();
};
el("busSearch").oninput = busTable;
el("mapTab").onclick = () => mapView();
el("schematicTab").onclick = electricalView;
el("fit").onclick = () => {
  if (map && !el("map").hidden)
    map.fitBounds([...geo.values()], { padding: [35, 35] });
  else el("schematicWrap").scrollLeft = 0;
};
el("motion").onclick = () => {
  motion = !motion;
  document.body.classList.toggle("no-motion", !motion);
  el("motion").textContent = "Motion " + (motion ? "on" : "off");
  el("motion").setAttribute("aria-pressed", String(motion));
  if (run) network();
};
if (!motion) {
  document.body.classList.add("no-motion");
  el("motion").textContent = "Motion off";
  el("motion").setAttribute("aria-pressed", "false");
}
el("presentation").onclick = () => {
  const active = document.body.classList.toggle("presentation");
  el("presentation").textContent = active
    ? "Exit presentation"
    : "Presentation view";
  el("presentation").setAttribute("aria-pressed", String(active));
  if (map) setTimeout(() => map.invalidateSize(), 100);
};
el("export").onclick = () => {
  const a = document.createElement("a"),
    url = URL.createObjectURL(
      new Blob(
        [
          JSON.stringify(
            { run, metrics, policyComparisons: window.policyResults || [] },
            null,
            2,
          ),
        ],
        { type: "application/json" },
      ),
    );
  a.href = url;
  a.download = `goev-${run.scenario.id}-presentation.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 500);
};
for (const link of document.querySelectorAll("nav a"))
  link.addEventListener("click", () => {
    document
      .querySelectorAll("nav a")
      .forEach((a) => a.classList.remove("active"));
    link.classList.add("active");
  });
document.addEventListener("visibilitychange", () => {
  if (document.hidden) stopPlay();
});
window.addEventListener("pagehide", stopPlay);
window.addEventListener(
  "scroll",
  () => {
    hideTip();
    const sections = ["overview", "network", "fleet", "comparison", "details"];
    let current = "overview";
    for (const id of sections)
      if (el(id).getBoundingClientRect().top < 150) current = id;
    document
      .querySelectorAll("nav a")
      .forEach((a) =>
        a.classList.toggle("active", a.getAttribute("href") === "#" + current),
      );
  },
  { passive: true },
);
act(async () => {
  scenarios = await api("/scenarios");
  for (const s of scenarios) {
    const option = document.createElement("option");
    option.value = s.id;
    option.textContent = s.id.replaceAll("-", " ");
    el("scenario").append(option);
  }
  el("scenario").value = "moderate-shortage";
  fillConfig();
  const id = localStorage.getItem("goev-v2g-run");
  if (id) {
    try {
      run = await api("/runs/" + id);
      el("scenario").value = scenarios.some((s) => s.id === run.scenario.id)
        ? run.scenario.id
        : "moderate-shortage";
      el("policy").value = run.policy;
      el("config").value = JSON.stringify(run.scenario, null, 2);
      await render();
    } catch (e) {
      localStorage.removeItem("goev-v2g-run");
      run = null;
    }
  }
});
