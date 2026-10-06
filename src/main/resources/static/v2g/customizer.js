"use strict";
// The builder edits scenario inputs, never the ledger of an already running experiment.
let entityEditor = null;
const draftKey = (id) => "goev-v2g-draft-" + id;
function canonicalInputs(value, key = "") {
  if (Array.isArray(value)) {
    const items = value.map((item) => canonicalInputs(item));
    return key === "connectors" ? items.sort() : items;
  }
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((name) => [name, canonicalInputs(value[name], name)]),
    );
  return value;
}
function draftScenario() {
  const sc = JSON.parse(el("config").value);
  if (
    !Array.isArray(sc.fleet) ||
    !Array.isArray(sc.stations) ||
    !Array.isArray(sc.branches)
  )
    throw Error("Scenario must contain fleet, stations and branches arrays.");
  return sc;
}
window.loadScenarioDraft = function (fallback) {
  try {
    const saved = localStorage.getItem(draftKey(fallback.id));
    return saved ? JSON.parse(saved) : fallback;
  } catch {
    return fallback;
  }
};
function writeDraft(sc) {
  el("config").value = JSON.stringify(sc, null, 2);
  clearPolicyComparison();
  window.saveScenarioDraft();
}
window.saveScenarioDraft = function () {
  try {
    const sc = draftScenario();
    localStorage.setItem(draftKey(sc.id), JSON.stringify(sc));
    window.renderScenarioBuilder();
  } catch (e) {
    el("draftStatus").textContent =
      "The scenario JSON is incomplete or browser draft storage is unavailable: " +
      e.message;
  }
};
window.renderScenarioBuilder = function () {
  let sc;
  try {
    sc = draftScenario();
  } catch {
    el("draftStatus").textContent =
      "Select a scenario, or fix its JSON in Engineering details before editing the fleet.";
    return;
  }
  el("draftEvCount").textContent = "(" + sc.fleet.length + ")";
  el("draftStationCount").textContent = "(" + sc.stations.length + ")";
  const same =
    run &&
    JSON.stringify(canonicalInputs(sc)) ===
      JSON.stringify(canonicalInputs(run.scenario));
  el("draftStatus").textContent = same
    ? "These inputs match the current experiment. Edits are saved as a draft and apply when you start a new run."
    : "Custom inputs configure the next experiment. Your current run is unchanged. Drafts are saved in this browser.";
  for (const [type, list, target] of [
    ["ev", sc.fleet, "draftEvs"],
    ["station", sc.stations, "draftStations"],
  ]) {
    el(target).replaceChildren();
    for (const entity of list) {
      const row = document.createElement("div");
      row.className = "draft-row";
      const info = document.createElement("div"),
        title = document.createElement("strong"),
        description = document.createElement("p");
      title.textContent = entity.id;
      description.textContent =
        type === "ev"
          ? `Bus ${entity.bus} · ${n(entity.usableKwh, 0)} kWh · SOC ${n(entity.soc, 0)}% · reserve ${n(entity.reserveSoc, 0)}% · ${n(entity.maxGridKw, 0)} kW`
          : `Bus ${entity.bus} · ${entity.totalPorts} ports · ${n(entity.portKw, 0)} kW/port · ${n(entity.siteKw, 0)} kW site`;
      info.append(title, description);
      const actions = document.createElement("div");
      for (const [label, handler] of [
        ["Edit", () => openEntity(type, entity.id)],
        ["Remove", () => removeEntity(type, entity.id)],
      ]) {
        const button = document.createElement("button");
        button.className = "ghost small";
        button.textContent = label;
        button.disabled = busy;
        button.setAttribute("aria-label", label + " " + entity.id);
        button.onclick = handler;
        actions.append(button);
      }
      row.append(info, actions);
      el(target).append(row);
    }
    if (!list.length) {
      const note = document.createElement("p");
      note.className = "muted";
      note.textContent =
        type === "ev"
          ? "No EVs in this scenario. Add a vehicle to explore support."
          : "No stations in this scenario. Add a bidirectional station at a bus.";
      el(target).append(note);
    }
  }
};
function nextId(list, prefix) {
  let i = 1;
  while (list.some((e) => e.id === prefix + i)) i++;
  return prefix + i;
}
function fields(type) {
  const common = [
    [
      "id",
      "Unique ID",
      "text",
      null,
      null,
      "Use a distinct name for this " +
        (type === "ev" ? "vehicle." : "station."),
    ],
    [
      "bus",
      type === "ev" ? "Starting bus" : "Connection bus",
      "bus",
      null,
      null,
      "Choose a bus in the existing electrical network.",
    ],
  ];
  return type === "ev"
    ? [
        ...common,
        [
          "usableKwh",
          "Usable battery capacity (kWh)",
          "number",
          0.01,
          null,
          "Already adjusted for battery health.",
        ],
        ["soc", "Initial battery charge / SOC (%)", "number", 0, 100],
        ["reserveSoc", "Owner minimum reserve (%)", "number", 0, 100],
        ["maxGridKw", "Maximum discharge to grid (kW)", "number", 0.01, null],
        ["consumptionKwhKm", "Travel consumption (kWh/km)", "number", 0, null],
        ["onwardKwh", "Extra onward-travel reserve (kWh)", "number", 0, null],
        [
          "departureHour",
          "Owner departure (hours from start)",
          "number",
          0.001,
          48,
        ],
        [
          "maxTravelKm",
          "Maximum station travel distance (km)",
          "number",
          0,
          null,
        ],
        [
          "connector",
          "Connector",
          "text",
          null,
          null,
          "Must match a station connector, e.g. CCS.",
        ],
        ["willing", "Owner agrees to provide support", "checkbox"],
        [
          "bidirectional",
          "Vehicle supports bidirectional discharge",
          "checkbox",
        ],
      ]
    : [
        ...common,
        ["totalPorts", "Physical ports", "integer", 1, null],
        [
          "externalOccupiedPorts",
          "Ports occupied outside this experiment",
          "integer",
          0,
          null,
        ],
        ["portKw", "Maximum output per port (kW)", "number", 0.01, null],
        ["siteKw", "Combined station output limit (kW)", "number", 0.01, null],
        [
          "connectors",
          "Compatible connectors",
          "text",
          null,
          null,
          "Comma-separated names, e.g. CCS, CHAdeMO.",
        ],
        ["online", "Station is initially online", "checkbox"],
        [
          "bidirectional",
          "Station supports bidirectional discharge",
          "checkbox",
        ],
      ];
}
function openEntity(type, id = null) {
  if (busy) return;
  stopPlay();
  try {
    const sc = draftScenario(),
      list = type === "ev" ? sc.fleet : sc.stations;
    const existing = id ? list.find((e) => e.id === id) : null;
    if (id && !existing) throw Error("This entry no longer exists.");
    entityEditor = {
      type,
      originalId: id,
      original: existing ? structuredClone(existing) : null,
    };
    const buses = [
      ...new Set([1, ...sc.branches.flatMap((b) => [b.from, b.to])]),
    ].sort((a, b) => a - b);
    const value =
      existing ||
      (type === "ev"
        ? {
            id: nextId(list, "EV-"),
            bus: buses.at(-1),
            usableKwh: 60,
            soc: 70,
            reserveSoc: 20,
            maxGridKw: 20,
            consumptionKwhKm: 0.2,
            onwardKwh: 1,
            departureHour: sc.settings.horizonHours + 1,
            maxTravelKm: 20,
            connector: "CCS",
            willing: true,
            bidirectional: true,
            telemetryHour: 0,
          }
        : {
            id: nextId(list, "V2G-"),
            bus: 14,
            totalPorts: 2,
            externalOccupiedPorts: 0,
            portKw: 20,
            siteKw: 40,
            connectors: ["CCS"],
            online: true,
            bidirectional: true,
            telemetryHour: 0,
          });
    el("entityTitle").textContent =
      (id ? "Edit " : "Add ") +
      (type === "ev" ? "electric vehicle" : "bidirectional EVCS");
    el("entityHelp").textContent =
      "Saved inputs apply to a new experiment. Travel and discharge remain simulated; electrical feasibility is checked by the backend.";
    el("entityError").hidden = true;
    el("entityFields").replaceChildren();
    for (const [name, label, kind, min, max, hint] of fields(type)) {
      const wrap = document.createElement("label"),
        input = document.createElement(kind === "bus" ? "select" : "input");
      input.id = "entity-" + name;
      input.name = name;
      input.required = kind !== "checkbox";
      if (kind === "bus") {
        for (const bus of buses) {
          const option = document.createElement("option");
          option.value = bus;
          option.textContent = "Bus " + bus;
          input.append(option);
        }
        input.value = value[name];
      } else if (kind === "checkbox") {
        input.type = "checkbox";
        input.checked = Boolean(value[name]);
        wrap.className = "check-field";
      } else {
        input.type = kind === "integer" ? "number" : kind;
        if (kind === "number" || kind === "integer") {
          input.step = kind === "integer" ? "1" : "any";
          if (min !== null) input.min = min;
          if (max !== null) input.max = max;
        }
        if (kind === "text") input.maxLength = 100;
        input.value = Array.isArray(value[name])
          ? value[name].join(", ")
          : value[name];
      }
      const caption = document.createElement("span");
      caption.textContent = label;
      wrap.append(caption, input);
      if (hint) {
        const note = document.createElement("small");
        note.textContent = hint;
        wrap.append(note);
      }
      el("entityFields").append(wrap);
    }
    el("entityDialog").showModal();
    el("entity-id").focus();
  } catch (e) {
    el("error").textContent = e.message;
    el("error").hidden = false;
  }
}
el("entityForm").onsubmit = (e) => {
  e.preventDefault();
  try {
    if (busy) throw Error("Wait for the current calculation to finish.");
    const sc = draftScenario(),
      { type, originalId, original } = entityEditor,
      list = type === "ev" ? sc.fleet : sc.stations;
    const entity = original ? structuredClone(original) : { telemetryHour: 0 };
    for (const [name, , kind] of fields(type)) {
      const input = el("entity-" + name);
      entity[name] =
        kind === "checkbox"
          ? input.checked
          : kind === "text"
            ? input.value.trim()
            : Number(input.value);
      if (
        (kind === "number" || kind === "integer" || kind === "bus") &&
        !Number.isFinite(entity[name])
      )
        throw Error("All numeric inputs must be finite.");
      if (kind === "integer" && !Number.isInteger(entity[name]))
        throw Error("Port counts must be whole numbers.");
    }
    if (!entity.id) throw Error("Enter a unique ID.");
    if (list.some((item) => item.id === entity.id && item.id !== originalId))
      throw Error("That ID already exists in this scenario.");
    if (type === "ev") {
      if (entity.reserveSoc > entity.soc)
        throw Error("Owner reserve cannot exceed initial SOC.");
      if (!entity.connector) throw Error("Enter a connector name.");
    } else {
      entity.connectors = [
        ...new Set(
          entity.connectors
            .split(",")
            .map((s) => s.trim())
            .filter(Boolean),
        ),
      ];
      if (!entity.connectors.length)
        throw Error("Enter at least one connector.");
      if (entity.externalOccupiedPorts > entity.totalPorts)
        throw Error("Occupied ports cannot exceed physical ports.");
    }
    if (originalId) {
      const index = list.findIndex((item) => item.id === originalId);
      if (index < 0) throw Error("This entry no longer exists.");
      list[index] = entity;
    } else list.push(entity);
    writeDraft(sc);
    el("entityDialog").close();
  } catch (error) {
    el("entityError").textContent = error.message;
    el("entityError").hidden = false;
  }
};
function removeEntity(type, id) {
  if (busy) return;
  stopPlay();
  try {
    const sc = draftScenario(),
      key = type === "ev" ? "fleet" : "stations";
    sc[key] = sc[key].filter((entity) => entity.id !== id);
    writeDraft(sc);
  } catch (e) {
    el("error").textContent = e.message;
    el("error").hidden = false;
  }
}
el("builderToggle").onclick = () => {
  const body = el("builderBody");
  body.hidden = !body.hidden;
  el("builderToggle").setAttribute("aria-expanded", String(!body.hidden));
  if (!body.hidden) window.renderScenarioBuilder();
};
el("addEv").onclick = () => openEntity("ev");
el("addStation").onclick = () => openEntity("station");
el("applyDraft").onclick = () => el("start").click();
el("resetDraft").onclick = () => {
  if (busy) return;
  stopPlay();
  const base = selected();
  localStorage.removeItem(draftKey(base.id));
  el("config").value = JSON.stringify(base, null, 2);
  clearPolicyComparison();
  window.renderScenarioBuilder();
};
for (const id of ["closeEntity", "cancelEntity"])
  el(id).onclick = () => el("entityDialog").close();
