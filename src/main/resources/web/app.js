"use strict";

// Everything that came from a player (a nickname, a folder or station name, a thermostat's label) is put
// on the page with textContent, never as HTML.

const POLL_MS = 2000;
const MAX_NAME = 32;
const $ = (id) => document.getElementById(id);

const ATTENTION = {
  no_units: "No equipment linked",
  no_vents: "No vents linked",
  units_off: "Equipment switched off",
  cant_keep_up: "Can't keep up",
  undersized: "Undersized",
};
const MODES = { cooling: "Cooling", heating: "Heating", idle: "Idle" };
const SKY = { clear: "Clear", rain: "Rain", snow: "Snow", thunder: "Thunder" };
const WIND = { calm: "Calm", breezy: "Breezy", windy: "Windy", gale: "Gale" };
const BAND = {
  good: "Good",
  moderate: "Moderate",
  unhealthy_for_sensitive_groups: "Unhealthy for sensitive groups",
  unhealthy: "Unhealthy",
  very_unhealthy: "Very unhealthy",
  hazardous: "Hazardous",
};

let units = "F";
let writable = false;
let lastKey = "";
let data = null;               // what is on the page now
let editing = false;           // while a name is being typed the page is left alone
const forecastOpen = new Set(); // keys of the thermostats whose forecast is showing

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

const show = (f) => (units === "C" ? (f - 32) * 5 / 9 : f);
const fmt = (f) => (Number.isFinite(f) ? show(f).toFixed(1) : "–");
const whole = (f) => (Number.isFinite(f) ? Math.round(show(f)) + "°" : "–");
const speed = (ms) => (units === "C" ? Math.round(ms * 3.6) + " km/h" : Math.round(ms * 2.236936) + " mph");
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

function toast(message) {
  const node = el("div", "toast", message);
  node.setAttribute("role", "status");
  document.body.appendChild(node);
  setTimeout(() => node.remove(), 3500);
}

async function post(path, body) {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-EZStats": "1" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    let message = "Something went wrong";
    try { message = (await response.json()).error || message; } catch (_) { /* keep the default */ }
    throw new Error(message);
  }
  try { return await response.json(); } catch (_) { return {}; }
}

function pencilIcon() {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 20 20");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.7");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  const path = document.createElementNS(ns, "path");
  path.setAttribute("d", "M13.5 3.5l3 3L7 16H4v-3l9.5-9.5z");
  svg.appendChild(path);
  return svg;
}

function iconButton(label, onClick) {
  const button = el("button", "edit");
  button.type = "button";
  button.title = label;
  button.setAttribute("aria-label", label);
  button.appendChild(pencilIcon());
  button.addEventListener("click", onClick);
  return button;
}

// --- one inline text form, used to rename a thermostat or a folder and to make a folder -----------

function endEditing() {
  editing = false;
  lastKey = "";
  $("folder-form").replaceChildren();
  refresh();
}

/**
 * A small form with a text field. `save(value)` may throw to show its message under the field; when it
 * returns, editing ends and the page reloads from the server.
 */
function inlineForm({ value, placeholder, label, submit, hint, save }) {
  editing = true;
  const form = el("form", "rename");
  const input = el("input");
  input.type = "text";
  input.maxLength = MAX_NAME;
  input.value = value;
  input.placeholder = placeholder;
  input.setAttribute("aria-label", label);
  const error = el("p", "error");
  error.hidden = true;

  const actions = el("div", "rename-actions");
  const ok = el("button", "small solid", submit);
  ok.type = "submit";
  const cancel = el("button", "small", "Cancel");
  cancel.type = "button";
  actions.append(ok, cancel);
  form.append(input, actions);
  if (hint) form.appendChild(el("span", "rename-hint", hint));
  form.appendChild(error);

  cancel.addEventListener("click", endEditing);
  form.addEventListener("keydown", (event) => { if (event.key === "Escape") endEditing(); });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    ok.disabled = true;
    error.hidden = true;
    try {
      await save(input.value);
      endEditing();
    } catch (e) {
      error.textContent = e.message;
      error.hidden = false;
      ok.disabled = false;
    }
  });
  setTimeout(() => { input.focus(); input.select(); }, 0);
  return form;
}

function renameThermostat(t, titles) {
  titles.replaceChildren(inlineForm({
    value: t.nickname || "",
    placeholder: t.label,
    label: "Nickname for " + t.label,
    submit: "Save",
    hint: "Leave it empty to use the sign's name.",
    save: (value) => post("/api/thermostat/nickname", { key: t.key, nickname: value }),
  }));
}

function startNewFolder() {
  if (editing) return;
  $("folder-form").replaceChildren(inlineForm({
    value: "",
    placeholder: "Folder name",
    label: "New folder name",
    submit: "Create",
    save: (value) => post("/api/folders", { name: value }),
  }));
}

function renameFolder(folder, head) {
  if (editing) return;
  head.replaceChildren(inlineForm({
    value: folder.name,
    placeholder: folder.name,
    label: "Rename folder " + folder.name,
    submit: "Save",
    save: (value) => post("/api/folders/rename", { id: folder.id, name: value }),
  }));
}

async function deleteFolder(folder) {
  if (!confirm(`Delete the folder "${folder.name}"? Its thermostats aren't deleted: they go back to Unfiled.`)) return;
  try { await post("/api/folders/delete", { id: folder.id }); lastKey = ""; refresh(); }
  catch (e) { toast(e.message); }
}

async function moveToFolder(t, folderId) {
  try { await post("/api/thermostat/folder", { key: t.key, folder: folderId }); lastKey = ""; refresh(); }
  catch (e) { toast(e.message); lastKey = ""; refresh(); }
}

// --- weather ----------------------------------------------------------------------------------

function stationOf(t) {
  return t.weather && data.state.stations ? data.state.stations[t.weather.station] : null;
}

function dayLabel(n) {
  return n === 0 ? "Today" : n === 1 ? "Tomorrow" : `In ${n} days`;
}

/** "12 mph from the W, Breezy"; short: "W 12 mph". */
function windText(w, short) {
  if (short) return `${w.from ? w.from + " " : ""}${speed(w.speedMs)}`;
  const parts = [`${speed(w.speedMs)}${w.from ? " from the " + w.from : ""}`];
  if (w.level) parts.push(WIND[w.level] || w.level);
  return parts.join(", ");
}

function weatherRow(t, node) {
  const station = stationOf(t);
  if (!station) {
    node.appendChild(el("div", "wx none", "No weather station in range"));
    return;
  }
  const today = station.forecast.find((d) => d.daysAhead === 0) || station.forecast[0];
  const row = el("div", "wx");
  const now = el("div", "wx-now");
  if (today) now.appendChild(el("span", "sky sky-" + today.sky, SKY[today.sky] || today.sky));
  now.appendChild(el("span", "wx-temp", `${whole(station.outdoorF)} outside`));
  if (today) {
    now.appendChild(el("span", "wx-range", `H ${whole(today.highF)}  L ${whole(today.lowF)}`));
    if (today.rain > 0) now.appendChild(el("span", "wx-range", `${Math.round(today.rain * 100)}% rain`));
  }
  row.appendChild(now);

  const open = forecastOpen.has(t.key);
  const toggle = el("button", "link small-link", open ? "Hide forecast" : "Forecast");
  toggle.type = "button";
  toggle.setAttribute("aria-expanded", String(open));
  toggle.addEventListener("click", () => {
    if (forecastOpen.has(t.key)) forecastOpen.delete(t.key); else forecastOpen.add(t.key);
    render(data);
  });
  row.appendChild(toggle);
  node.appendChild(row);
  if (open) node.appendChild(forecastPanel(t, station));
}

/** What a station measures right now, as short phrases. */
function stationBits(station) {
  const bits = [];
  if (station.wind) bits.push("Wind " + windText(station.wind));
  if (station.air) bits.push(`Air ${station.air.aqi}, ${BAND[station.air.band] || station.air.band}`);
  if (station.lightningStrikes > 0) bits.push(`${station.lightningStrikes} lightning strike${station.lightningStrikes === 1 ? "" : "s"}`);
  return bits;
}

function forecastPanel(t, station) {
  const panel = el("div", "forecast");
  const head = el("div", "fc-head");
  head.appendChild(el("b", "", station.label));
  head.append(` · ${t.weather.distance} blocks away`);
  panel.appendChild(head);

  const bits = stationBits(station);
  if (bits.length) panel.appendChild(el("div", "fc-now", bits.join(" · ")));

  if (!station.forecast.length) {
    panel.appendChild(el("div", "fc-now", "This station isn't forecasting right now."));
    return panel;
  }
  panel.appendChild(forecastList(station));
  return panel;
}

/** The seven days of a station: day, sky, high and low, rain, and a line of notes. */
function forecastList(station) {
  const list = el("ul", "fc-days");
  for (const d of station.forecast) {
    const li = el("li");
    li.appendChild(el("span", "fc-day", dayLabel(d.daysAhead)));
    li.appendChild(el("span", "sky sky-" + d.sky, SKY[d.sky] || d.sky));
    li.appendChild(el("span", "fc-temps", `${whole(d.highF)} / ${whole(d.lowF)}`));
    li.appendChild(el("span", "fc-rain", d.rain > 0 ? `${Math.round(d.rain * 100)}%` : "–"));
    const extra = [];
    if (d.note) extra.push(d.note);
    if (d.thunder >= 0.05) extra.push(`${Math.round(d.thunder * 100)}% thunder`);
    if (d.wind) extra.push(windText(d.wind, true));
    if (d.aqi !== undefined) extra.push("AQI " + d.aqi);
    if (extra.length) li.appendChild(el("span", "fc-note", extra.join(" · ")));
    list.appendChild(li);
  }
  return list;
}

// --- the Weather tab: every station ---------------------------------------------------------------

let tab = location.hash === "#weather" ? "weather" : "thermostats";
let weatherTab = false;  // whether this server has the tab on
let wxKey = "";
let wxLoaded = false;   // the Weather tab was opened by a link: load it once the page knows the server has it
const WX_POLL_MS = 10000;

/** The names of the player's thermostats that this station is the nearest for. */
function nearestFor(stationKey) {
  if (!data) return [];
  return data.state.thermostats
    .filter((t) => t.weather && t.weather.station === stationKey)
    .map((t) => t.nickname || t.label);
}

function stationCard(station) {
  const working = station.working;
  const node = el("article", "card station" + (working ? "" : " idle"));

  const head = el("div", "head");
  const titles = el("div", "titles");
  titles.appendChild(el("h3", "name", station.label));
  const where = [`${station.x}, ${station.y}, ${station.z}`];
  if (station.areaRadius) where.push(`measures ${station.areaRadius} blocks around`);
  titles.appendChild(el("div", "sub", where.join(" · ")));
  head.appendChild(titles);
  head.appendChild(el("span", "pill " + (working ? "good" : ""), working ? "Forecasting" : station.enabled ? (station.intact ? "Waiting" : "Damaged") : "Off"));
  node.appendChild(head);

  const mine = nearestFor(station.key);
  if (mine.length) {
    const chips = el("div", "chips");
    chips.appendChild(el("span", "chip mine", "Nearest for " + mine.join(", ")));
    node.appendChild(chips);
  }

  if (!working) {
    node.appendChild(el("div", "why", !station.enabled ? "Switched off by an admin."
      : !station.intact ? "Its tower is damaged, so it can't forecast until it is repaired."
      : "It hasn't measured the weather yet."));
    return node;
  }

  const today = station.forecast.find((d) => d.daysAhead === 0) || station.forecast[0];
  const reading = el("div", "reading");
  const now = el("div");
  const big = el("div", "big", fmt(station.outdoorF));
  big.appendChild(el("span", "unit", "°" + units));
  now.appendChild(big);
  now.appendChild(el("div", "cap", "Outside now"));
  reading.appendChild(now);

  const facts = el("div", "wx-facts");
  if (today) {
    const sky = el("span");
    sky.appendChild(el("span", "sky sky-" + today.sky, SKY[today.sky] || today.sky));
    facts.appendChild(sky);
    const range = el("span");
    range.append("High ", el("b", "", whole(today.highF)), " · Low ", el("b", "", whole(today.lowF)));
    facts.appendChild(range);
  }
  reading.appendChild(facts);
  node.appendChild(reading);

  const bits = stationBits(station);
  if (bits.length) node.appendChild(el("div", "fc-now", bits.join(" · ")));
  node.appendChild(forecastList(station));
  return node;
}

function renderWeather(wx) {
  units = wx.units === "C" ? "C" : "F";
  const groups = $("wx-groups");
  groups.replaceChildren();
  const byWorld = new Map();
  for (const s of wx.stations) {
    const world = s.world || "Unknown world";
    if (!byWorld.has(world)) byWorld.set(world, []);
    byWorld.get(world).push(s);
  }
  for (const [world, list] of byWorld) {
    const group = el("section", "group");
    const head = el("div", "group-head");
    const titles = el("div", "group-title");
    titles.appendChild(el("h2", "", world));
    titles.appendChild(el("span", "group-meta", `${list.length} station${list.length === 1 ? "" : "s"}`));
    head.appendChild(titles);
    group.appendChild(head);
    const grid = el("div", "grid station-grid");
    for (const s of list) grid.appendChild(stationCard(s));
    group.appendChild(grid);
    groups.appendChild(group);
  }
  $("wx-empty").hidden = wx.stations.length > 0;
  $("foot").textContent = `Weather refreshed ${new Date(wx.generatedAt).toLocaleTimeString()}`;
}

async function loadWeather() {
  if (tab !== "weather" || !weatherTab) return;
  try {
    const response = await fetch("/api/weather", { cache: "no-store" });
    if (response.status === 401) return renderUnlinked();
    if (!response.ok) throw new Error("HTTP " + response.status);
    const wx = await response.json();
    // Same rule as the thermostats page: only rebuild when something changed.
    const key = JSON.stringify([wx.stations, wx.units, data ? data.state.thermostats.map((t) => t.weather) : null]);
    if (key !== wxKey) { wxKey = key; renderWeather(wx); }
    else $("foot").textContent = `Weather refreshed ${new Date(wx.generatedAt).toLocaleTimeString()}`;
  } catch (e) {
    $("foot").textContent = "Can't reach the server. Trying again…";
  }
}

function showTab(name) {
  tab = weatherTab && name === "weather" ? "weather" : "thermostats";
  $("view-thermostats").hidden = tab !== "thermostats";
  $("view-weather").hidden = tab !== "weather";
  for (const id of ["thermostats", "weather"]) {
    $("tab-" + id).setAttribute("aria-selected", String(tab === id));
  }
  history.replaceState(null, "", tab === "weather" ? "#weather" : location.pathname);
  if (tab === "weather") { wxKey = ""; loadWeather(); }
  else if (data) { lastKey = ""; stamp(data.state); }
}

// --- cards ------------------------------------------------------------------------------------

function card(t, folders) {
  const node = el("article", "card thermo " + (t.enabled ? t.mode : "off"));
  const title = t.nickname || t.label;

  const head = el("div", "head");
  const titles = el("div", "titles");
  const row = el("div", "name-row");
  row.appendChild(el("h3", "name", title));
  row.appendChild(iconButton("Rename " + title, () => { if (!editing) renameThermostat(t, titles); }));
  titles.appendChild(row);

  const where = [];
  if (t.nickname) where.push(t.label);
  where.push(`${t.world} ${t.x}, ${t.y}, ${t.z}`);
  if (t.pool) where.push("pool");
  if (t.mini) where.push("mini");
  titles.appendChild(el("div", "sub", where.join(" · ")));
  head.appendChild(titles);
  head.appendChild(el("span", "pill " + (t.enabled ? t.mode : ""), t.enabled ? (MODES[t.mode] || t.mode) : "Off"));
  node.appendChild(head);

  const reading = el("div", "reading");
  const room = el("div");
  const big = el("div", "big", fmt(t.roomF));
  big.appendChild(el("span", "unit", "°" + units));
  room.appendChild(big);
  room.appendChild(el("div", "cap", t.pool ? "Water now" : "Room now"));
  reading.appendChild(room);

  const target = el("div", "target");
  target.appendChild(el("div", "num", fmt(t.targetF) + "°"));
  target.appendChild(el("div", "cap", "Target"));
  if (writable) {
    const stepper = el("div", "stepper");
    for (const [label, delta] of [["−", -1], ["+", 1]]) {
      const button = el("button", "small", label);
      button.type = "button";
      button.setAttribute("aria-label", (delta < 0 ? "Lower" : "Raise") + " the target of " + title);
      button.addEventListener("click", () => step(t, delta));
      stepper.appendChild(button);
    }
    target.appendChild(stepper);
  }
  reading.appendChild(target);
  node.appendChild(reading);

  const stats = el("div", "stats");
  const stat = (label, value) => {
    const item = el("span");
    item.append(label + " ", el("b", "", value));
    stats.appendChild(item);
  };
  if (Number.isFinite(t.humidity)) stat("Humidity", Math.round(t.humidity) + "%");
  stat("Equipment", `${t.runningUnits}/${t.totalUnits} running`);
  if (t.profile && t.profile !== "normal") stat("Profile", cap(t.profile));
  node.appendChild(stats);

  if (t.attention && t.attention.length) {
    const chips = el("div", "chips");
    for (const item of t.attention) chips.appendChild(el("span", "chip", ATTENTION[item] || item));
    node.appendChild(chips);
  }

  weatherRow(t, node);

  const foot = el("div", "card-foot");
  if (folders.length) {
    const label = el("label", "folder-pick");
    label.append("Folder ");
    const select = el("select");
    select.setAttribute("aria-label", "Folder for " + title);
    select.appendChild(new Option("Unfiled", ""));
    for (const f of folders) select.appendChild(new Option(f.name, f.id));
    select.value = t.folder || "";
    select.addEventListener("change", () => moveToFolder(t, select.value));
    label.appendChild(select);
    foot.appendChild(label);
  }
  if (writable) {
    const power = el("button", "small", t.enabled ? "Switch off" : "Switch on");
    power.type = "button";
    power.addEventListener("click", async () => {
      try { await post("/api/thermostat/enabled", { key: t.key, enabled: !t.enabled }); lastKey = ""; refresh(); }
      catch (e) { toast(e.message); }
    });
    foot.appendChild(power);
  }
  if (foot.childNodes.length) node.appendChild(foot);
  return node;
}

// One step is a whole degree in the server's units: 1 °F or 1 °C.
async function step(t, delta) {
  const stepF = units === "C" ? delta * 9 / 5 : delta;
  try {
    await post("/api/thermostat/target", { key: t.key, targetF: t.targetF + stepF });
    lastKey = "";
    refresh();
  } catch (e) {
    toast(e.message);
  }
}

// --- page -------------------------------------------------------------------------------------

function worldMeta(w) {
  const parts = [];
  if (w.season) {
    parts.push(`${cap(w.season.name)}, day ${w.season.day} of ${w.season.days}${w.season.paused ? " (paused)" : ""}`);
  }
  if (w.outdoor) {
    let outside = `${fmt(w.outdoor.airF)}°${units} outside`;
    if (w.outdoor.weather) outside += `, ${w.outdoor.weather.toLowerCase()}`;
    parts.push(outside);
  }
  return parts.join(" · ");
}

function section(folder, list, folders) {
  const group = el("section", "group");
  const head = el("div", "group-head");
  const titles = el("div", "group-title");
  titles.appendChild(el("h2", "", folder ? folder.name : "Unfiled"));
  titles.appendChild(el("span", "group-meta", String(list.length)));
  head.appendChild(titles);
  if (folder) {
    const actions = el("div", "group-actions");
    actions.appendChild(iconButton("Rename folder " + folder.name, () => renameFolder(folder, head)));
    const del = el("button", "link small-link", "Delete");
    del.type = "button";
    del.addEventListener("click", () => deleteFolder(folder));
    actions.appendChild(del);
    head.appendChild(actions);
  }
  group.appendChild(head);

  if (list.length === 0) {
    group.appendChild(el("p", "folder-empty", "Nothing here yet. Use the Folder menu on a thermostat to move it in."));
  } else {
    const grid = el("div", "grid");
    for (const t of list) grid.appendChild(card(t, folders));
    group.appendChild(grid);
  }
  return group;
}

function render(d) {
  data = d;
  const state = d.state;
  units = state.units === "C" ? "C" : "F";
  writable = state.writable === true;

  $("unlinked").hidden = true;
  $("linked").hidden = false;
  $("who").hidden = false;
  $("who-name").textContent = d.player;
  $("readonly").hidden = writable;

  weatherTab = d.weatherTab === true;
  $("tabs").hidden = !weatherTab;
  if (!weatherTab) tab = "thermostats";
  $("view-thermostats").hidden = tab !== "thermostats";
  $("view-weather").hidden = tab !== "weather";
  $("tab-thermostats").setAttribute("aria-selected", String(tab === "thermostats"));
  $("tab-weather").setAttribute("aria-selected", String(tab === "weather"));
  if (tab === "weather" && !wxLoaded) { wxLoaded = true; loadWeather(); }

  const worlds = $("worlds");
  worlds.replaceChildren();
  for (const w of state.worlds) {
    const meta = worldMeta(w);
    const box = el("span", "world");
    box.appendChild(el("b", "", w.name));
    if (meta) box.append(" · " + meta);
    worlds.appendChild(box);
  }

  const folders = state.folders || [];
  const groups = $("groups");
  groups.replaceChildren();
  if (folders.length === 0) {
    if (state.thermostats.length) {
      const grid = el("div", "grid");
      for (const t of state.thermostats) grid.appendChild(card(t, folders));
      groups.appendChild(grid);
    }
  } else {
    const byFolder = new Map(folders.map((f) => [f.id, []]));
    const unfiled = [];
    for (const t of state.thermostats) (byFolder.get(t.folder) || unfiled).push(t);
    for (const f of folders) groups.appendChild(section(f, byFolder.get(f.id), folders));
    if (unfiled.length) groups.appendChild(section(null, unfiled, folders));
  }
  $("empty").hidden = state.thermostats.length > 0;
  stamp(state);
}

function stamp(state) {
  if (tab === "weather") return; // the Weather tab keeps its own footer
  $("foot").textContent = `EZvac ${state.ezvacVersion || ""} · refreshed ${new Date(state.generatedAt).toLocaleTimeString()}`;
}

function renderUnlinked() {
  $("unlinked").hidden = false;
  $("linked").hidden = true;
  $("who").hidden = true;
  lastKey = "";
}

async function refresh() {
  try {
    const response = await fetch("/api/state", { cache: "no-store" });
    if (response.status === 401) return renderUnlinked();
    if (!response.ok) throw new Error("HTTP " + response.status);
    const d = await response.json();
    if (editing) return;
    // Don't rebuild the page when nothing changed: it keeps focus on a button being used.
    const s = d.state;
    const key = JSON.stringify([s.thermostats, s.worlds, s.folders, s.stations, d.player]);
    if (key !== lastKey) { lastKey = key; render(d); }
    else stamp(s);
  } catch (e) {
    $("foot").textContent = "Can't reach the server. Trying again…";
  }
}

$("new-folder").addEventListener("click", startNewFolder);
$("tab-thermostats").addEventListener("click", () => showTab("thermostats"));
$("tab-weather").addEventListener("click", () => showTab("weather"));
setInterval(loadWeather, WX_POLL_MS);

$("login").addEventListener("submit", async (event) => {
  event.preventDefault();
  const error = $("login-error");
  const submit = $("login-submit");
  error.hidden = true;
  submit.disabled = true;
  try {
    await post("/api/login", { username: $("username").value.trim(), password: $("password").value });
    $("password").value = "";
    await refresh();
  } catch (e) {
    error.textContent = e.message;
    error.hidden = false;
  } finally {
    submit.disabled = false;
  }
});

$("logout").addEventListener("click", async () => {
  try { await post("/api/logout", {}); } catch (_) { /* signed out or not, show the sign-in page */ }
  editing = false;
  data = null;
  renderUnlinked();
});

refresh();
setInterval(refresh, POLL_MS);
