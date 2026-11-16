"use strict";

// Everything that came from a player (a nickname, a home or station name, a device's label) is put
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
let fullscreen = false;        // true only while a card is being built for the full-screen view
let detail = null;             // the device or station shown full screen: { kind: "device" | "station", key }

// Homes the player folded away. Kept in this browser only: it is a view preference, not account data.
const COLLAPSED_KEY = "ezstats.collapsedHomes";
const NO_HOME = "~none";       // the id of the "No home" section
const collapsedHomes = new Set(loadCollapsed());

function loadCollapsed() {
  try {
    const saved = JSON.parse(localStorage.getItem(COLLAPSED_KEY) || "[]");
    return Array.isArray(saved) ? saved.filter((id) => typeof id === "string") : [];
  } catch (_) { return []; } // storage blocked or damaged: start with every home open
}

function saveCollapsed() {
  try { localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...collapsedHomes])); } catch (_) { /* not saved */ }
}

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
  // Holding down a button repeats its message: show it once.
  if ([...document.querySelectorAll(".toast")].some((t) => t.textContent === message)) return;
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

/** A 20x20 line icon drawn from one SVG path. */
function iconSvg(d) {
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
  path.setAttribute("d", d);
  svg.appendChild(path);
  return svg;
}

const PENCIL = "M13.5 3.5l3 3L7 16H4v-3l9.5-9.5z";
const EXPAND = "M12 3h5v5M8 17H3v-5M17 3l-6 6M3 17l6-6";
const CHEVRON = "M5 7.5l5 5 5-5";

function iconButton(label, onClick, path, extraClass) {
  const button = el("button", "edit" + (extraClass ? " " + extraClass : ""));
  button.type = "button";
  button.title = label;
  button.setAttribute("aria-label", label);
  button.appendChild(iconSvg(path || PENCIL));
  button.addEventListener("click", onClick);
  return button;
}

// --- one inline text form, used to rename a device or a home and to make a home -------------------

function endEditing() {
  editing = false;
  lastKey = "";
  $("home-form").replaceChildren();
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

function renameDevice(d, titles) {
  titles.replaceChildren(inlineForm({
    value: d.nickname || "",
    placeholder: d.label || typeName(d),
    label: "Nickname for " + deviceName(d),
    submit: "Save",
    hint: "Leave it empty to use its own name.",
    save: (value) => post("/api/device/nickname", { key: d.key, nickname: value }),
  }));
}

function startNewHome() {
  if (editing) return;
  $("home-form").replaceChildren(inlineForm({
    value: "",
    placeholder: "Home name",
    label: "New home name",
    submit: "Create",
    save: (value) => post("/api/homes", { name: value }),
  }));
}

function renameHome(home, head) {
  if (editing) return;
  head.replaceChildren(inlineForm({
    value: home.name,
    placeholder: home.name,
    label: "Rename home " + home.name,
    submit: "Save",
    save: (value) => post("/api/homes/rename", { id: home.id, name: value }),
  }));
}

async function deleteHome(home) {
  if (!confirm(`Delete the home "${home.name}"? Its devices aren't deleted: they go back to "No home".`)) return;
  try { await post("/api/homes/delete", { id: home.id }); lastKey = ""; refresh(); }
  catch (e) { toast(e.message); }
}

async function moveToHome(d, homeId) {
  try { await post("/api/device/home", { key: d.key, home: homeId }); }
  catch (e) { toast(e.message); }
  lastKey = "";
  refresh();
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

  // Full screen always shows the forecast, so there is nothing to toggle.
  const open = fullscreen || forecastOpen.has(t.key);
  if (!fullscreen) {
    const toggle = el("button", "link small-link", open ? "Hide forecast" : "Forecast");
    toggle.type = "button";
    toggle.setAttribute("aria-expanded", String(open));
    toggle.addEventListener("click", () => {
      if (forecastOpen.has(t.key)) forecastOpen.delete(t.key); else forecastOpen.add(t.key);
      render(data);
    });
    row.appendChild(toggle);
  }
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
  if (fullscreen) panel.appendChild(forecastStrip(station));
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

let tab = location.hash === "#weather" ? "weather" : "devices";
let weatherTab = false;   // whether this server has the tab on
let wxKey = "";
let wxLoaded = false;     // the Weather tab was opened by a link: load it once the page knows the server has it
let wx = null;            // the stations as last loaded
let wxSearch = "";
let wxWorld = "";         // "" for every world
let wxStatus = "all";     // all | working | idle
const wxOpen = new Set(); // keys of the stations whose day-by-day details are showing
const WX_POLL_MS = 10000;

const SKY_ICON = { clear: "☀️", rain: "🌧️", snow: "❄️", thunder: "⛈️" };

/** The names of the player's thermostats that this station is the nearest for. */
function nearestFor(stationKey) {
  if (!data) return [];
  return data.state.devices
    .filter((t) => t.weather && t.weather.station === stationKey)
    .map((t) => deviceName(t));
}

/** Why a station isn't forecasting, and the word for its pill. */
function stationStatus(station) {
  if (station.working) return { word: "Forecasting", why: "" };
  if (!station.enabled) return { word: "Off", why: "Switched off by an admin." };
  if (!station.intact) return { word: "Damaged", why: "Its tower is damaged: it forecasts again once it is repaired." };
  return { word: "Waiting", why: "It hasn't measured the weather yet." };
}

function dayShort(n) {
  return n === 0 ? "Today" : n === 1 ? "Tmrw" : "+" + n;
}

/** The seven days side by side: day, sky, high and low, and the chance of rain. */
function forecastStrip(station) {
  const strip = el("div", "strip");
  strip.setAttribute("role", "list");
  for (const d of station.forecast) {
    const col = el("div", "strip-day");
    col.setAttribute("role", "listitem");
    col.appendChild(el("span", "strip-label", dayShort(d.daysAhead)));
    const icon = el("span", "strip-icon", SKY_ICON[d.sky] || "");
    icon.setAttribute("role", "img");
    icon.setAttribute("aria-label", SKY[d.sky] || d.sky);
    icon.title = SKY[d.sky] || d.sky;
    col.appendChild(icon);
    col.appendChild(el("b", "strip-hi", whole(d.highF)));
    col.appendChild(el("span", "strip-lo", whole(d.lowF)));
    col.appendChild(el("span", "strip-rain", d.rain > 0 ? Math.round(d.rain * 100) + "%" : " "));
    strip.appendChild(col);
  }
  return strip;
}

function stationCard(station) {
  const node = el("article", "card station");

  const head = el("div", "head");
  const titles = el("div", "titles");
  titles.appendChild(el("h3", "name", station.label));
  titles.appendChild(el("div", "sub", `${station.x}, ${station.y}, ${station.z}`));
  head.appendChild(titles);
  const status = stationStatus(station);
  const side = el("div", "head-side");
  side.appendChild(el("span", "pill" + (station.working ? " good" : ""), status.word));
  if (!fullscreen) {
    side.appendChild(iconButton("Open " + station.label + " full screen", () => openDetail("station", station.key), EXPAND, "expand"));
  }
  head.appendChild(side);
  node.appendChild(head);

  const mine = nearestFor(station.key);
  if (mine.length) {
    const chips = el("div", "chips");
    chips.appendChild(el("span", "chip mine", "Nearest for " + mine.join(", ")));
    node.appendChild(chips);
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
    facts.appendChild(el("span", "sky sky-" + today.sky, SKY[today.sky] || today.sky));
    const range = el("span");
    range.append("High ", el("b", "", whole(today.highF)), " · Low ", el("b", "", whole(today.lowF)));
    facts.appendChild(range);
  }
  reading.appendChild(facts);
  node.appendChild(reading);

  const bits = stationBits(station);
  if (bits.length) node.appendChild(el("div", "fc-now", bits.join(" · ")));

  node.appendChild(forecastStrip(station));

  const open = fullscreen || wxOpen.has(station.key);
  if (!fullscreen) {
    const toggle = el("button", "link small-link", open ? "Hide day-by-day" : "Day-by-day details");
    toggle.type = "button";
    toggle.setAttribute("aria-expanded", String(open));
    toggle.addEventListener("click", () => {
      if (wxOpen.has(station.key)) wxOpen.delete(station.key); else wxOpen.add(station.key);
      drawWeather();
    });
    node.appendChild(toggle);
  }
  if (open) node.appendChild(forecastList(station));
  return node;
}

/** A station that isn't forecasting: one line, not a card. */
function idleRow(station) {
  const status = stationStatus(station);
  const li = el("li", "idle-row");
  const main = el("div", "idle-main");
  main.appendChild(el("b", "", station.label));
  main.appendChild(el("span", "sub", `${station.world || "Unknown world"} · ${station.x}, ${station.y}, ${station.z}`));
  li.appendChild(main);
  li.appendChild(el("span", "idle-why", status.why));
  li.appendChild(el("span", "pill", status.word));
  return li;
}

function weatherSection(title, count, content) {
  const group = el("section", "group");
  const head = el("div", "group-head");
  const titles = el("div", "group-title");
  titles.appendChild(el("h2", "", title));
  titles.appendChild(el("span", "group-meta", String(count)));
  head.appendChild(titles);
  group.append(head, content);
  return group;
}

function stationGrid(list) {
  const grid = el("div", "grid station-grid");
  for (const s of list) grid.appendChild(stationCard(s));
  return grid;
}

function visibleStations() {
  const q = wxSearch.trim().toLowerCase();
  return wx.stations.filter((s) =>
    (!wxWorld || (s.world || "") === wxWorld) &&
    (wxStatus === "all" || (wxStatus === "working") === s.working) &&
    (!q || s.label.toLowerCase().includes(q) || (s.world || "").toLowerCase().includes(q)));
}

/** Draws the tab from the stations last loaded and the search and filters. */
function drawWeather() {
  if (!wx) return;
  units = wx.units === "C" ? "C" : "F";
  const all = wx.stations;
  $("wx-empty").hidden = all.length > 0;
  $("wx-tools").hidden = all.length === 0;
  $("wx-summary").hidden = all.length === 0;

  const worlds = [...new Set(all.map((s) => s.world || ""))].filter(Boolean);
  if (wxWorld && !worlds.includes(wxWorld)) wxWorld = "";
  const select = $("wx-world");
  select.replaceChildren(new Option("All worlds", ""), ...worlds.map((w) => new Option(w, w)));
  select.value = wxWorld;
  select.hidden = worlds.length < 2;
  for (const button of document.querySelectorAll("#wx-status button")) {
    button.setAttribute("aria-pressed", String(button.dataset.status === wxStatus));
  }

  const working = all.filter((s) => s.working).length;
  const idle = all.length - working;
  $("wx-summary").textContent = `${all.length} station${all.length === 1 ? "" : "s"} · ${working} forecasting`
    + (idle ? ` · ${idle} not forecasting` : "");

  const shown = visibleStations();
  const mineStations = shown.filter((s) => s.working && nearestFor(s.key).length);
  const others = shown.filter((s) => s.working && !nearestFor(s.key).length);
  const notWorking = shown.filter((s) => !s.working);

  const near = $("wx-near");
  near.replaceChildren();
  if (mineStations.length) near.appendChild(weatherSection("Near your thermostats", mineStations.length, stationGrid(mineStations)));

  const groups = $("wx-groups");
  groups.replaceChildren();
  const byWorld = new Map();
  for (const s of others) {
    const world = s.world || "Unknown world";
    if (!byWorld.has(world)) byWorld.set(world, []);
    byWorld.get(world).push(s);
  }
  // With nothing pinned above, the worlds need no "other" qualifier.
  for (const [world, list] of byWorld) {
    groups.appendChild(weatherSection(mineStations.length ? `${world} · other stations` : world, list.length, stationGrid(list)));
  }

  const idleBox = $("wx-idle");
  idleBox.replaceChildren();
  if (notWorking.length) {
    const list = el("ul", "idle-list");
    for (const s of notWorking) list.appendChild(idleRow(s));
    idleBox.appendChild(weatherSection("Not forecasting", notWorking.length, list));
  }

  $("wx-none").hidden = all.length === 0 || shown.length > 0;
  $("foot").textContent = `Weather refreshed ${new Date(wx.generatedAt).toLocaleTimeString()}`;
}

function renderWeather(loaded) {
  wx = loaded;
  drawWeather();
  drawDetail();
}

async function loadWeather() {
  if (tab !== "weather" || !weatherTab) return;
  try {
    const response = await fetch("/api/weather", { cache: "no-store" });
    if (response.status === 401) return renderUnlinked();
    if (!response.ok) throw new Error("HTTP " + response.status);
    const loaded = await response.json();
    // Same rule as the thermostats page: only rebuild when something changed.
    const key = JSON.stringify([loaded.stations, loaded.units, data ? data.state.devices.map((t) => t.weather) : null]);
    if (key !== wxKey) { wxKey = key; renderWeather(loaded); }
    else $("foot").textContent = `Weather refreshed ${new Date(loaded.generatedAt).toLocaleTimeString()}`;
  } catch (e) {
    $("foot").textContent = "Can't reach the server. Trying again…";
  }
}

function showTab(name) {
  tab = weatherTab && name === "weather" ? "weather" : "devices";
  $("view-devices").hidden = tab !== "devices";
  $("view-weather").hidden = tab !== "weather";
  for (const id of ["devices", "weather"]) {
    $("tab-" + id).setAttribute("aria-selected", String(tab === id));
  }
  history.replaceState(null, "", tab === "weather" ? "#weather" : location.pathname);
  if (tab === "weather") { wxKey = ""; loadWeather(); }
  else if (data) { lastKey = ""; stamp(data.state); }
}

// --- devices: what every card shares ---------------------------------------------------------------

/** What a device is called on the page: its owner's nickname, its own label, or what kind of thing it is. */
function deviceName(d) {
  return d.nickname || d.label || typeName(d);
}

/** Where a device is, and what it belongs to, as short phrases for the line under its name. */
function deviceWhere(d) {
  const parts = [];
  if (d.nickname && d.label) parts.push(d.label);
  if (d.kind !== "thermostat" && !d.label && d.nickname) parts.push(typeName(d));
  parts.push(`${d.world} ${d.x}, ${d.y}, ${d.z}`);
  if (d.pool) parts.push("pool");
  if (d.mini) parts.push("mini");
  if (d.system) parts.push("system " + d.system);
  return parts;
}

/** The top of a card: its name with a rename pencil, where it is, and a pill on the right. */
function deviceHead(d, pill) {
  const head = el("div", "head");
  const titles = el("div", "titles");
  const row = el("div", "name-row");
  const title = deviceName(d);
  row.appendChild(el("h3", "name", title));
  row.appendChild(iconButton("Rename " + title, () => { if (!editing) renameDevice(d, titles); }));
  titles.appendChild(row);
  titles.appendChild(el("div", "sub", deviceWhere(d).join(" · ")));
  head.appendChild(titles);
  const side = el("div", "head-side");
  if (pill) side.appendChild(pill);
  if (!fullscreen && expandable(d)) {
    side.appendChild(iconButton("Open " + title + " full screen", () => { if (!editing) openDetail("device", d.key); }, EXPAND, "expand"));
  }
  if (side.childNodes.length) head.appendChild(side);
  return head;
}

/** Thermostats, fridges, freezers and mini fridges can be opened full screen. */
function expandable(d) {
  return d.kind === "thermostat" || (d.kind === "unit" && FRIDGES.includes(d.type));
}

// --- the full-screen view of one device or station ------------------------------------------------

/**
 * Draws the open item, or hides the view when nothing is open (or the item is gone). It is drawn again
 * whenever the page's data changes, so what it shows stays current.
 */
function drawDetail() {
  let node = null;
  let title = "";
  fullscreen = true;
  try {
    if (detail && detail.kind === "device" && data) {
      const d = data.state.devices.find((x) => x.key === detail.key);
      if (d) { node = deviceCard(d, data.state.homes || []); title = deviceName(d); }
    } else if (detail && detail.kind === "station" && wx) {
      const s = wx.stations.find((x) => x.key === detail.key);
      if (s) { node = stationCard(s); title = s.label; }
    }
  } finally {
    fullscreen = false;
  }
  if (!node) detail = null;
  $("detail").hidden = !node;
  document.body.classList.toggle("has-detail", !!node);
  if (!node) { $("detail-body").replaceChildren(); return; }
  $("detail-title").textContent = title;
  $("detail").setAttribute("aria-label", title);
  $("detail-body").replaceChildren(node);
}

function openDetail(kind, key) {
  if (editing) return;
  const wasOpen = detail !== null;
  detail = { kind, key };
  drawDetail();
  if (!wasOpen) { $("detail").scrollTop = 0; $("detail-close").focus(); }
}

function closeDetail() {
  if (editing) endEditing();
  detail = null;
  drawDetail();
}

/** The "Home" menu of a card, or null when the player has made no homes. */
function homePicker(d, homes) {
  if (!homes.length) return null;
  const label = el("label", "home-pick");
  label.append("Home ");
  const select = el("select");
  select.setAttribute("aria-label", "Home for " + deviceName(d));
  select.appendChild(new Option("No home", ""));
  for (const h of homes) select.appendChild(new Option(h.name, h.id));
  select.value = d.home || "";
  select.addEventListener("change", () => moveToHome(d, select.value));
  label.appendChild(select);
  return label;
}

/**
 * Sends a change to EZvac through the server. Whatever happens, read the device again a moment later
 * (the server reads EZvac again on the next tick) so the page shows what it really is now.
 */
async function act(key, action, value) {
  try {
    const result = await post("/api/device/control", { key, action, value });
    if (result.adjusted && result.message) toast(result.message);
  } catch (e) {
    toast(e.message);
  }
  lastKey = "";
  setTimeout(refresh, 350);
}

/** A segmented control of choices; the one matching `current` is pressed. `onPick(name)` runs on a click. */
function segmented(label, names, current, onPick, nameOf) {
  const seg = el("div", "seg small-seg");
  seg.setAttribute("role", "group");
  seg.setAttribute("aria-label", label);
  for (const name of names) {
    const button = el("button", "", nameOf ? nameOf(name) : cap(name));
    button.type = "button";
    button.setAttribute("aria-pressed", String(current === name));
    button.addEventListener("click", () => {
      if (current === name) return;
      for (const other of seg.children) other.disabled = true;
      onPick(name);
    });
    seg.appendChild(button);
  }
  return seg;
}

/** A device's card: the Nest-style dial for a thermostat, a tile for everything else. */
function deviceCard(d, homes) {
  return d.kind === "thermostat" ? thermostatCard(d, homes) : deviceTile(d, homes);
}

/** Thermostats first, then humidity units and fridges, equipment, instruments, and vents last. */
const KIND_ORDER = { thermostat: 0, unit: 1, equipment: 2, instrument: 3, vent: 4 };

/** The cards of some devices: the dials in one grid, the tiles in another below it. */
function deviceGrids(list, homes) {
  const box = document.createDocumentFragment();
  const sorted = [...list].sort((a, b) => (KIND_ORDER[a.kind] ?? 9) - (KIND_ORDER[b.kind] ?? 9));
  const dials = sorted.filter((d) => d.kind === "thermostat");
  const tiles = sorted.filter((d) => d.kind !== "thermostat");
  if (dials.length) {
    const grid = el("div", "grid");
    for (const d of dials) grid.appendChild(deviceCard(d, homes));
    box.appendChild(grid);
  }
  if (tiles.length) {
    const grid = el("div", "grid tiles");
    for (const d of tiles) grid.appendChild(deviceCard(d, homes));
    box.appendChild(grid);
  }
  return box;
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

/** A home with the devices in it; `home` is null for the devices that are in no home. */
function homeSection(home, list, homes) {
  const id = home ? home.id : NO_HOME;
  const name = home ? home.name : "No home";
  const group = el("section", "group");
  const head = el("div", "group-head");
  const titles = el("div", "group-title");
  const content = el("div", "group-body");

  // The chevron folds the home's devices away; the choice is remembered in this browser.
  const toggle = iconButton("", () => flip(), CHEVRON, "collapse");
  function sync() {
    const folded = collapsedHomes.has(id);
    group.classList.toggle("collapsed", folded);
    content.hidden = folded;
    toggle.setAttribute("aria-expanded", String(!folded));
    const label = (folded ? "Expand " : "Collapse ") + name;
    toggle.title = label;
    toggle.setAttribute("aria-label", label);
  }
  function flip() {
    if (collapsedHomes.has(id)) collapsedHomes.delete(id); else collapsedHomes.add(id);
    saveCollapsed();
    sync();
  }
  const heading = el("h2", "", name);
  heading.addEventListener("click", flip);
  titles.append(toggle, heading);
  titles.appendChild(el("span", "group-meta", `${list.length} device${list.length === 1 ? "" : "s"}`));
  head.appendChild(titles);
  if (home) {
    const actions = el("div", "group-actions");
    actions.appendChild(iconButton("Rename home " + home.name, () => renameHome(home, head)));
    const del = el("button", "link small-link", "Delete");
    del.type = "button";
    del.addEventListener("click", () => deleteHome(home));
    actions.appendChild(del);
    head.appendChild(actions);
  }
  if (list.length === 0) {
    content.appendChild(el("p", "home-empty", "Nothing here yet. Use the Home menu on a device to move it in."));
  } else {
    content.appendChild(deviceGrids(list, homes));
  }
  group.append(head, content);
  sync();
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
  if (!writable) {
    $("readonly").textContent = `${state.readOnlyReason || "Changing devices is not available."} `
      + "You can still rename devices and sort them into homes here.";
  }

  weatherTab = d.weatherTab === true;
  $("tabs").hidden = !weatherTab;
  if (!weatherTab) tab = "devices";
  $("view-devices").hidden = tab !== "devices";
  $("view-weather").hidden = tab !== "weather";
  $("tab-devices").setAttribute("aria-selected", String(tab === "devices"));
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

  const homes = state.homes || [];
  // Forget folded homes that no longer exist.
  const before = collapsedHomes.size;
  for (const id of [...collapsedHomes]) if (id !== NO_HOME && !homes.some((h) => h.id === id)) collapsedHomes.delete(id);
  if (collapsedHomes.size !== before) saveCollapsed();
  const groups = $("groups");
  groups.replaceChildren();
  if (homes.length === 0) {
    if (state.devices.length) groups.appendChild(deviceGrids(state.devices, homes));
  } else {
    const byHome = new Map(homes.map((h) => [h.id, []]));
    const loose = [];
    for (const device of state.devices) (byHome.get(device.home) || loose).push(device);
    for (const h of homes) groups.appendChild(homeSection(h, byHome.get(h.id), homes));
    if (loose.length) groups.appendChild(homeSection(null, loose, homes));
  }
  $("empty").hidden = state.devices.length > 0;
  drawDetail();
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
  detail = null;
  drawDetail();
}

async function refresh() {
  try {
    const response = await fetch("/api/state", { cache: "no-store" });
    if (response.status === 401) return renderUnlinked();
    if (!response.ok) throw new Error("HTTP " + response.status);
    const d = await response.json();
    if (editing || changing.size > 0) return; // not while a name is typed or a dial is being turned
    // Don't rebuild the page when nothing changed: it keeps focus on a button being used.
    const s = d.state;
    const key = JSON.stringify([s.devices, s.worlds, s.homes, s.stations, d.player]);
    if (key !== lastKey) { lastKey = key; render(d); }
    else stamp(s);
  } catch (e) {
    $("foot").textContent = "Can't reach the server. Trying again…";
  }
}

$("detail-close").addEventListener("click", closeDetail);
document.addEventListener("keydown", (event) => {
  // Escape inside a rename form cancels the rename, not the full-screen view behind it.
  if (event.key === "Escape" && detail && !editing && !(event.target.closest && event.target.closest("form.rename"))) closeDetail();
});
$("new-home").addEventListener("click", startNewHome);
$("tab-devices").addEventListener("click", () => showTab("devices"));
$("tab-weather").addEventListener("click", () => showTab("weather"));
$("wx-search").addEventListener("input", (event) => { wxSearch = event.target.value; drawWeather(); });
$("wx-world").addEventListener("change", (event) => { wxWorld = event.target.value; drawWeather(); });
for (const button of document.querySelectorAll("#wx-status button")) {
  button.addEventListener("click", () => { wxStatus = button.dataset.status; drawWeather(); });
}
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
