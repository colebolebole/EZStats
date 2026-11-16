"use strict";

// Tiles for every EZvac device that isn't a thermostat: equipment, humidifiers and dehumidifiers, air
// purifiers, fridges and freezers, vents, and the sensors and monitors. Controls only appear when the
// server lets the website change devices. Shares the helpers of app.js and dial.js.

const TYPE_NAME = {
  room: "Thermostat",
  pool: "Pool thermostat",
  heat_pump: "Heat pump",
  air_conditioner: "Air conditioner",
  furnace: "Furnace",
  boiler: "Boiler",
  pool_heat_pump: "Pool heat pump",
  dehumidifier: "Dehumidifier",
  humidifier: "Humidifier",
  air_purifier: "Air purifier",
  fridge: "Fridge",
  deep_freezer: "Deep freezer",
  refrigerator: "Refrigerator",
  mini_fridge: "Mini fridge",
  air: "Air vent",
  water: "Water vent",
  thermometer: "Thermometer",
  hygrometer: "Hygrometer",
  thermo_hygrometer: "Thermo-hygrometer",
  temperature_sensor: "Temperature sensor",
  humidity_sensor: "Humidity sensor",
  temperature_humidity_sensor: "Temperature and humidity sensor",
  weather_sensor: "Weather sensor",
  sensor_hub: "Sensor hub",
  rpm_monitor: "RPM monitor",
  settings_panel: "Settings panel",
  air_monitor: "Air monitor",
  air_sensor: "Air sensor",
  wind_sensor: "Wind sensor",
};

const UNIT_STATE = {
  off: "Off",
  idle: "Idle",
  running: "Running",
  emptying: "Emptying",
  no_filter: "No filter",
  filter_spent: "Filter spent",
};

/** What kind of device this is, in words. */
function typeName(d) {
  return TYPE_NAME[d.type] || cap(String(d.type || "device").replace(/_/g, " "));
}

const FRIDGES = ["fridge", "deep_freezer", "refrigerator", "mini_fridge"];

// --- small parts ------------------------------------------------------------------------------------

/** A label and a bold value, as in a card's row of facts. */
function fact(box, name, value) {
  const item = el("span");
  item.append(name + " ", el("b", "", value));
  box.appendChild(item);
}

/** A bar for a fraction between 0 and 1: a tank, a filter, frost. */
function meter(name, fraction, warnAbove) {
  const row = el("div", "meter");
  const pct = Math.round(Math.min(1, Math.max(0, fraction)) * 100);
  row.appendChild(el("span", "meter-name", `${name} ${pct}%`));
  const track = el("div", "meter-track");
  const fill = el("div", "meter-fill" + (warnAbove !== undefined && fraction >= warnAbove ? " warn" : ""));
  fill.style.width = pct + "%";
  track.appendChild(fill);
  row.appendChild(track);
  return row;
}

/** A button that changes a device; shown only when the server lets the website do that. */
function actionButton(text, label, onClick, pressed) {
  const button = el("button", "small", text);
  button.type = "button";
  button.setAttribute("aria-label", label);
  if (pressed !== undefined) button.setAttribute("aria-pressed", String(pressed));
  button.addEventListener("click", () => { button.disabled = true; onClick(); });
  return button;
}

/**
 * A value with - and + that sends the new value once the hand has rested, like the thermostat dial.
 * `format(v)` writes a value; `step(v, direction)` gives the next one; `send(v)` returns a promise.
 */
function stepper(d, field, name, value, format, step, send) {
  const row = el("div", "stepper-row");
  row.appendChild(el("span", "stepper-name", name));
  const num = el("b", "stepper-value", format(value));
  const lower = roundButton("Lower the " + name.toLowerCase() + " of " + deviceName(d), () => move(-1), "−");
  const raise = roundButton("Raise the " + name.toLowerCase() + " of " + deviceName(d), () => move(1), "+");
  lower.classList.add("tiny");
  raise.classList.add("tiny");
  row.append(lower, num, raise);

  const id = d.key + ":" + field;
  let current = value;
  let timer = null;
  function move(direction) {
    current = step(current, direction);
    num.textContent = format(current);
    changing.add(id);
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      send(current).finally(() => changing.delete(id));
    }, SEND_AFTER_MS);
  }
  return row;
}

/** The server's units: a stored degrees-F whole number shown in the page's units, stepped by one shown degree. */
const fahrenheitText = (f) => `${Math.round(show(f))}°${units}`;
function fahrenheitStep(f, direction) {
  if (units !== "C") return f + direction;
  return Math.round((Math.round(show(f)) + direction) * 9 / 5 + 32);
}

// --- one tile per kind ------------------------------------------------------------------------------

function deviceTile(d, homes) {
  const node = el("article", "card tile tile-" + d.kind);
  node.appendChild(el("div", "kind", typeName(d)));

  let pill = null;
  const body = el("div", "tile-body");
  switch (d.kind) {
    case "equipment": pill = equipmentTile(d, body); break;
    case "unit": pill = unitTile(d, body); break;
    case "instrument": instrumentTile(d, body); break;
    case "vent": ventTile(d, body); break;
    default: body.appendChild(el("div", "cap", "This kind of device isn't shown yet."));
  }
  node.appendChild(deviceHead(d, pill));
  node.appendChild(body);
  const remote = remoteChip(d);
  if (remote) {
    const chips = el("div", "chips");
    chips.appendChild(remote);
    node.appendChild(chips);
  }

  const picker = homePicker(d, homes);
  if (picker) {
    const foot = el("div", "card-foot");
    foot.appendChild(picker);
    node.appendChild(foot);
  }
  return node;
}

/** A heat pump, AC, furnace, boiler or pool heat pump. Its pill says what it is doing. */
function equipmentTile(d, body) {
  const facts = el("div", "stats");
  fact(facts, "Mode", cap(d.mode || "idle"));
  if (Number.isFinite(d.rpm)) fact(facts, "Motor", Math.round(d.rpm) + " rpm");
  if (d.tier) fact(facts, "Stage", "★".repeat(d.tier) + "☆".repeat(3 - d.tier));
  body.appendChild(facts);
  if (writable) {
    body.appendChild(actionButton(d.enabled ? "Switch off" : "Switch on",
      (d.enabled ? "Switch off " : "Switch on ") + deviceName(d), () => act(d.key, "equipment-enabled", !d.enabled)));
  }
  const text = !d.enabled ? "Off" : d.running ? (d.mode === "heating" ? "Heating" : d.mode === "cooling" ? "Cooling" : "Running") : "Idle";
  return el("span", "pill " + (d.enabled && d.running ? (d.mode === "heating" ? "heating" : "cooling") : ""), text);
}

/** A dehumidifier, humidifier, air purifier, fridge, freezer or mini fridge. */
function unitTile(d, body) {
  const fridge = FRIDGES.includes(d.type);
  const facts = el("div", "stats");

  if (d.type === "dehumidifier" || d.type === "humidifier") {
    body.appendChild(readout(Number.isFinite(d.humidity) ? Math.round(d.humidity) + "%" : "–", "Humidity here"));
    if (writable) {
      body.appendChild(stepper(d, "setpoint", "Target", d.setpoint, (v) => v + "%", (v, dir) => Math.min(100, Math.max(0, v + dir * 5)),
        (v) => act(d.key, "setpoint", v)));
    } else {
      fact(facts, "Target", d.setpoint + "%");
    }
    if (d.type === "dehumidifier" && Number.isFinite(d.tank)) body.appendChild(meter("Tank", d.tank, 0.8));
  } else if (d.type === "air_purifier") {
    // Always shown: until the purifier has measured the air there is no number, and the card says so.
    body.appendChild(d.air
      ? readout("AQI " + d.air.aqi, BAND[d.air.band] || d.air.band)
      : readout("AQI –", d.powered ? "Not measured yet" : "Switched off"));
    if (Number.isFinite(d.filter)) body.appendChild(meter("Filter", d.filter));
    if (writable) {
      const row = el("div", "profile-row");
      row.appendChild(el("span", "profile-label", "Fan"));
      row.appendChild(segmented("Fan of " + deviceName(d), ["low", "medium", "high", "auto"], d.fanMode, (name) => act(d.key, "fan", name)));
      body.appendChild(row);
    } else if (d.fanMode) {
      fact(facts, "Fan", cap(d.fanMode) + (d.fanMode === "auto" && d.fanSpeed ? ` (${d.fanSpeed})` : ""));
    }
  } else if (fridge) {
    fridgeBody(d, body, facts);
  }

  if (facts.childNodes.length) body.appendChild(facts);

  const power = writable
    ? actionButton(d.powered ? "Switch off" : "Switch on", (d.powered ? "Switch off " : "Switch on ") + deviceName(d), () => act(d.key, "powered", !d.powered))
    : null;
  const buttons = el("div", "tile-actions");
  if (power) buttons.appendChild(power);
  if (writable && d.cold && (d.type === "deep_freezer" || d.type === "refrigerator")) {
    buttons.appendChild(actionButton(d.cold.defrosting ? "Defrosting" : "Defrost now", "Defrost " + deviceName(d),
      () => act(d.key, "defrost", true)));
    if (d.type === "refrigerator") {
      buttons.appendChild(actionButton("Auto-defrost " + (d.cold.autoDefrost ? "on" : "off"), "Turn auto-defrost " + (d.cold.autoDefrost ? "off" : "on"),
        () => act(d.key, "auto-defrost", !d.cold.autoDefrost), d.cold.autoDefrost));
    }
  }
  if (buttons.childNodes.length) body.appendChild(buttons);

  const state = d.powered ? UNIT_STATE[d.state] || cap(d.state || "") : "Off";
  const tone = d.powered && d.state === "running" ? "good" : d.state === "no_filter" || d.state === "filter_spent" ? "warn" : "";
  return el("span", "pill " + tone, state);
}

function fridgeBody(d, body, facts) {
  const cold = d.cold || {};
  const compartment = (name, insideF, setpointF, field) => {
    if (Number.isFinite(insideF)) body.appendChild(readout(fahrenheitText(insideF), name + " inside"));
    if (setpointF === undefined) return;
    if (writable) {
      body.appendChild(stepper(d, field, name + " target", setpointF, fahrenheitText, fahrenheitStep, (v) => act(d.key, field, v)));
    } else {
      fact(facts, name + " target", fahrenheitText(setpointF));
    }
  };
  if (d.type === "refrigerator") {
    compartment("Fridge", cold.fridgeInsideF, cold.fridgeSetpointF, "fridge-setpoint");
    compartment("Freezer", d.insideF, d.setpoint, "setpoint");
  } else {
    compartment("Inside", d.insideF, d.setpoint, "setpoint");
    if (d.type === "mini_fridge" && Number.isFinite(cold.chillerF)) fact(facts, "Chiller", fahrenheitText(cold.chillerF));
  }
  if (cold.doorOpen) {
    const chips = el("div", "chips");
    chips.appendChild(el("span", "chip", "Door open"));
    body.appendChild(chips);
  }
  if (Number.isFinite(cold.frost)) body.appendChild(meter("Frost", cold.frost, 0.7));
  if (cold.defrosting) fact(facts, "", "Defrosting");
}

/** A big number with a small caption: what a card is mostly about. */
function readout(value, caption) {
  const box = el("div", "readout");
  box.appendChild(el("div", "readout-value", value));
  box.appendChild(el("div", "cap", caption));
  return box;
}

/** A thermometer, sensor, monitor, hub or panel: what it reads, or what it is linked to. */
function instrumentTile(d, body) {
  const r = d.reading || {};
  const parts = [];
  if (Number.isFinite(r.tempF)) parts.push(`${fmt(r.tempF)}°${units}`);
  if (Number.isFinite(r.humidity)) parts.push(`${Math.round(r.humidity)}%`);
  if (r.air) parts.push("AQI " + r.air.aqi);
  if (Number.isFinite(r.windMs)) parts.push(speed(r.windMs) + (r.windFrom ? " " + r.windFrom : ""));
  if (parts.length) {
    const caption = r.air ? BAND[r.air.band] || r.air.band : r.weather ? r.weather : r.windLevel ? WIND[r.windLevel] || r.windLevel : "Reads here";
    body.appendChild(readout(parts.join("  ·  "), caption));
  }
  const facts = el("div", "stats");
  if (d.links) {
    const what = d.type === "sensor_hub" ? "sensor" : "device";
    fact(facts, "Shows", `${d.links} ${what}${d.links === 1 ? "" : "s"}`);
  }
  if (!parts.length && !d.links) body.appendChild(el("div", "cap", "No reading of its own"));
  if (facts.childNodes.length) body.appendChild(facts);
}

function ventTile(d, body) {
  body.appendChild(el("div", "cap", d.type === "water" ? "Carries water for a pool or radiant system." : "Blows conditioned air into its room."));
}
