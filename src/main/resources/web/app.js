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
let dragging = null;           // the key of the device being dragged: the page is left alone then too
let draggingGroup = null;      // the organization, folder or home being dragged (the node of the tree)
let pickingUntil = 0;          // while a menu is open (until this time) the page is left alone: see below
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

// --- one inline form: rename a device or group, make a group, move a group -----------------------------

function endEditing() {
  editing = false;
  lastKey = "";
  $("home-form").replaceChildren();
  refresh();
}

/**
 * A small form. It has a text field (unless `noText`) and, with `choice`, a menu of
 * {label, options: [{value, label}], value}. `save(text, chosen)` may throw to show its message under the
 * form; when it returns, editing ends and the page reloads from the server.
 */
function inlineForm({ value, placeholder, label, submit, hint, save, choice, noText }) {
  editing = true;
  const form = el("form", "rename");
  let input = null;
  if (!noText) {
    input = el("input");
    input.type = "text";
    input.maxLength = MAX_NAME;
    input.value = value;
    input.placeholder = placeholder;
    input.setAttribute("aria-label", label);
    form.appendChild(input);
  }
  let select = null;
  if (choice) {
    const wrap = el("label", "rename-choice");
    wrap.append(choice.label + " ");
    select = el("select");
    select.setAttribute("aria-label", choice.label);
    for (const option of choice.options) select.appendChild(new Option(option.label, option.value));
    select.value = choice.value;
    wrap.appendChild(select);
    form.appendChild(wrap);
  }
  const error = el("p", "error");
  error.hidden = true;

  const actions = el("div", "rename-actions");
  const ok = el("button", "small solid", submit);
  ok.type = "submit";
  const cancel = el("button", "small", "Cancel");
  cancel.type = "button";
  actions.append(ok, cancel);
  form.appendChild(actions);
  if (hint) form.appendChild(el("span", "rename-hint", hint));
  form.appendChild(error);

  cancel.addEventListener("click", endEditing);
  form.addEventListener("keydown", (event) => { if (event.key === "Escape") endEditing(); });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    ok.disabled = true;
    error.hidden = true;
    try {
      await save(input ? input.value : "", select ? select.value : "");
      endEditing();
    } catch (e) {
      error.textContent = e.message;
      error.hidden = false;
      ok.disabled = false;
    }
  });
  setTimeout(() => { if (input) { input.focus(); input.select(); } else if (select) select.focus(); }, 0);
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

// --- organizations, folders and homes ---------------------------------------------------------------
//
// An organization sits at the top and holds folders and homes. A folder holds folders and homes. A home holds
// devices. Everything here is the signed-in player's own.

const GROUP_API = { org: "/api/orgs", folder: "/api/folders", home: "/api/homes" };
const GROUP_NOUN = { org: "organization", folder: "folder", home: "home" };
const GROUP_TITLE = { org: "Organization", folder: "Folder", home: "Home" };

/** Built from each snapshot: { nodes: Map(id -> node), roots, byHome: Map(id -> devices), loose }. */
let tree = { nodes: new Map(), roots: [], byHome: new Map(), loose: [] };
let arranging = false;      // the page is in "Arrange" mode: it shows the buttons that reorder
const neighbors = new Map(); // device key -> where its "earlier" and "later" buttons put it (set while drawing)

/** "Acme Co / Kyleton / House 1": where a group is, by name. */
function pathOf(id) {
  const names = [];
  for (let node = tree.nodes.get(id), guard = 0; node && guard < 12; node = tree.nodes.get(node.parent), guard++) {
    names.unshift(node.name);
  }
  return names.join(" / ");
}

/** Organizations and folders in tree order, labelled with their path, except `excludeId` and what it holds. */
function containerChoices(excludeId) {
  const choices = [{ value: "", label: "At the top" }];
  const walk = (nodes) => {
    for (const node of nodes) {
      if (node.kind === "home" || node.id === excludeId) continue;
      choices.push({ value: node.id, label: pathOf(node.id) });
      walk(node.children);
    }
  };
  walk(tree.roots);
  return choices;
}

function startNew(kind) {
  if (editing) return;
  const noun = GROUP_NOUN[kind];
  $("home-form").replaceChildren(inlineForm({
    value: "",
    placeholder: GROUP_TITLE[kind] + " name",
    label: "New " + noun + " name",
    submit: "Create",
    // An organization is always at the top; a folder or home can go inside one.
    choice: kind === "org" ? null : { label: "Inside", options: containerChoices(null), value: "" },
    save: (value, parent) => post(GROUP_API[kind], kind === "org" ? { name: value } : { name: value, parent }),
  }));
}

function renameGroup(node, head) {
  if (editing) return;
  head.replaceChildren(inlineForm({
    value: node.name,
    placeholder: node.name,
    label: "Rename " + GROUP_NOUN[node.kind] + " " + node.name,
    submit: "Save",
    save: (value) => post(GROUP_API[node.kind] + "/rename", { id: node.id, name: value }),
  }));
}

/** Moves a folder or home, with what it holds, into an organization or folder (or to the top). */
function startMove(node, head) {
  if (editing) return;
  head.replaceChildren(inlineForm({
    noText: true,
    label: "Move " + node.name,
    submit: "Move",
    choice: { label: "Move " + node.name + " into", options: containerChoices(node.id), value: node.parent },
    save: (_, parent) => post("/api/groups/move", { id: node.id, parent }),
  }));
}

async function deleteGroup(node) {
  const held = {
    org: "Its folders and homes aren't deleted: they move up to the top.",
    folder: "What is in it isn't deleted: it moves up a level.",
    home: `Its devices aren't deleted: they go back to "No home".`,
  }[node.kind];
  if (!confirm(`Delete the ${GROUP_NOUN[node.kind]} "${node.name}"? ${held}`)) return;
  try { await post(GROUP_API[node.kind] + "/delete", { id: node.id }); lastKey = ""; refresh(); }
  catch (e) { toast(e.message); }
}

/** The groups of the same kind in the same place as this one, in order. */
function siblingsOf(node) {
  const parent = tree.nodes.get(node.parent);
  return (parent ? parent.children : tree.roots).filter((other) => other.kind === node.kind);
}

/** Moves a group one place earlier (-1) or later (+1) among its siblings. */
async function nudgeGroup(node, direction) {
  const siblings = siblingsOf(node);
  const at = siblings.indexOf(node);
  const target = direction < 0 ? siblings[at - 1] : siblings[at + 2];
  try { await post("/api/groups/order", { id: node.id, before: target ? target.id : "" }); }
  catch (e) { toast(e.message); }
  lastKey = "";
  refresh();
}

/** Moves a device just before another device of its place, or to the end of it ("" for the end). */
async function reorderDevice(d, before) {
  try { await post("/api/device/order", { key: d.key, before }); }
  catch (e) { toast(e.message); }
  lastKey = "";
  refresh();
}

// --- drag and drop: a device goes where it is dropped -----------------------------------------------
//
// Drag a card by its grip. Dropped on another device it goes just before that one (in that device's place);
// dropped on an organization, folder or home (its title or an empty part of it) it goes to the end of it;
// dropped on the strip that appears at the bottom, it is taken out of its group. The page doesn't redraw
// while a device is being dragged, and everything is sent to the server in one call when it is dropped.

let dropMark = null; // the element that shows where the device would go, and the class it has

function markDrop(el, className) {
  if (dropMark && dropMark.el === el && dropMark.className === className) return;
  clearDropMark();
  if (el) { el.classList.add(className); dropMark = { el, className }; }
}

function clearDropMark() {
  if (dropMark) dropMark.el.classList.remove(dropMark.className);
  dropMark = null;
}

function endDrag(redraw = true) {
  dragging = null;
  draggingGroup = null;
  clearDropMark();
  document.body.classList.remove("is-dragging");
  $("drop-out").hidden = true;
  for (const card of document.querySelectorAll(".card.dragging, .group.dragging-group")) {
    card.classList.remove("dragging", "dragging-group");
  }
  if (redraw) { lastKey = ""; refresh(); }
}

const OUT_DEVICE = "Drop here to take it out of its group";
const OUT_GROUP = "Drop here to move it to the top level";

async function dropDevice(key, group, before) {
  try { await post("/api/device/place", { key, group, before }); }
  catch (e) { toast(e.message); }
  lastKey = "";
  refresh();
}

/** The grip of a card: dragging it drags the whole card. Null where the page can't drag. */
function dragGrip(d) {
  const grip = el("span", "grip");
  grip.draggable = true;
  grip.title = "Drag to move " + deviceName(d) + " into a home, folder or organization, or before another device";
  grip.appendChild(iconSvg("M7 4h.01M13 4h.01M7 10h.01M13 10h.01M7 16h.01M13 16h.01"));
  grip.addEventListener("dragstart", (event) => {
    if (editing) { event.preventDefault(); return; }
    dragging = d.key;
    event.dataTransfer.setData("text/plain", d.key);
    event.dataTransfer.effectAllowed = "move";
    const card = grip.closest(".card");
    if (card) {
      event.dataTransfer.setDragImage(card, 28, 20);
      card.classList.add("dragging");
    }
    document.body.classList.add("is-dragging");
    $("drop-out").textContent = OUT_DEVICE;
    $("drop-out").hidden = tree.nodes.size === 0;
  });
  grip.addEventListener("dragend", () => endDrag());
  return grip;
}

/** The grip of an organization, folder or home title: dragging it drags the whole group, with what is in it. */
function groupGrip(node, section, head) {
  const grip = el("span", "grip group-grip");
  grip.draggable = true;
  grip.title = "Drag to move " + node.name + (node.kind === "org"
    ? " before or after another organization"
    : " into an organization or folder, or beside another");
  grip.appendChild(iconSvg("M7 4h.01M13 4h.01M7 10h.01M13 10h.01M7 16h.01M13 16h.01"));
  grip.addEventListener("dragstart", (event) => {
    if (editing) { event.preventDefault(); return; }
    draggingGroup = node;
    event.dataTransfer.setData("text/plain", node.id);
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setDragImage(head, 24, 16);
    section.classList.add("dragging-group");
    document.body.classList.add("is-dragging");
    // Only something below the top needs the "top level" strip.
    $("drop-out").textContent = OUT_GROUP;
    $("drop-out").hidden = node.parent === "";
  });
  grip.addEventListener("dragend", () => endDrag());
  return grip;
}

/** Whether `node` is `ancestorId` or somewhere inside it. */
function isInside(node, ancestorId) {
  for (let at = node, steps = 0; at && steps < 12; at = tree.nodes.get(at.parent), steps++) {
    if (at.id === ancestorId) return true;
  }
  return false;
}

function beforePlan(target) {
  return { parent: target.parent, before: target.id, mark: "drop-line-before" };
}

function afterPlan(target, dragged) {
  const siblings = siblingsOf(target);
  let next = siblings[siblings.indexOf(target) + 1];
  if (next && next.id === dragged.id) next = siblings[siblings.indexOf(target) + 2];
  return { parent: target.parent, before: next ? next.id : "", mark: "drop-line-after" };
}

/**
 * What dropping the dragged group on this part of the page would do, or null if it can't go there.
 * {parent, before} is where it would land, and `mark` how to show it. On the title of a group: the top of
 * a folder beside the dragged folder means "before it", the rest of an organization's or folder's title
 * "into it"; on a home's title the top half means before it and the bottom half after it. On the empty
 * part of an organization or folder: into it. An organization only goes before or after another one.
 */
function groupPlan(section, target, isHead, event) {
  const g = draggingGroup;
  if (section.id === "drop-out") return g.parent === "" ? null : { parent: "", before: "", mark: "drop-target" };
  if (!target) return null;
  // Not onto itself or what it holds, and not on to whatever holds those either: that would quietly move it.
  if (target.id === g.id || isInside(target, g.id)) return STOP;
  const head = section.querySelector(":scope > .group-head");
  const rect = head.getBoundingClientRect();
  const frac = isHead ? (event.clientY - rect.top) / Math.max(1, rect.height) : 1;
  if (g.kind === "org") {
    if (target.kind !== "org" || !isHead) return null;
    return frac < 0.5 ? beforePlan(target) : afterPlan(target, g);
  }
  if (target.kind !== "home") {
    if (isHead && g.kind === target.kind && frac < 0.3) return beforePlan(target);
    return { parent: target.id, before: "", mark: "drop-target" };
  }
  if (g.kind === "home" && isHead) return frac < 0.5 ? beforePlan(target) : afterPlan(target, g);
  return null;
}

/** What {@link groupPlan} answers over the dragged group itself: nothing, and nothing behind it either. */
const STOP = "stop";

async function dropGroup(group, plan) {
  try { await post("/api/groups/place", { id: group.id, parent: plan.parent, before: plan.before }); }
  catch (e) { toast(e.message); }
  lastKey = "";
  refresh();
}

/** Makes a card take drops: a dragged device goes just before it, in the place the card's device is. */
function acceptOnCard(card, d) {
  card.addEventListener("dragover", (event) => {
    if (!dragging || dragging === d.key) return;
    event.preventDefault();
    event.stopPropagation();
    event.dataTransfer.dropEffect = "move";
    markDrop(card, "drop-before");
  });
  card.addEventListener("drop", (event) => {
    if (!dragging) return;
    event.preventDefault();
    event.stopPropagation();
    const key = dragging;
    endDrag(false);
    if (key !== d.key) dropDevice(key, d.home || "", d.key);
  });
}

/**
 * Makes part of a group take drops. A dragged device goes to the end of it (`id` is "" for no group). A
 * dragged group goes where {@link groupPlan} says: `node` is this group's node (none for the strip at the
 * bottom) and `isHead` whether this is its title.
 */
function acceptOnGroup(zone, section, id, node, isHead) {
  zone.addEventListener("dragover", (event) => {
    if (draggingGroup) {
      const plan = groupPlan(section, node, isHead, event);
      if (plan === STOP) { event.stopPropagation(); clearDropMark(); return; }
      if (!plan) return; // no place for it here: the drag goes on to whatever holds this
      event.preventDefault();
      event.stopPropagation();
      event.dataTransfer.dropEffect = "move";
      markDrop(section, plan.mark);
      return;
    }
    if (!dragging) return;
    event.preventDefault();
    event.stopPropagation();
    event.dataTransfer.dropEffect = "move";
    markDrop(section, "drop-target");
  });
  zone.addEventListener("drop", (event) => {
    if (draggingGroup) {
      const plan = groupPlan(section, node, isHead, event);
      if (plan === STOP) { event.stopPropagation(); return; }
      if (!plan) return;
      event.preventDefault();
      event.stopPropagation();
      const group = draggingGroup;
      endDrag(false);
      dropGroup(group, plan);
      return;
    }
    if (!dragging) return;
    event.preventDefault();
    event.stopPropagation();
    const key = dragging;
    endDrag(false);
    dropDevice(key, id, "");
  });
}

// Anything a drag passes over that isn't a place to drop clears the mark.
document.addEventListener("dragover", () => { if (dragging || draggingGroup) clearDropMark(); });
document.addEventListener("drop", (event) => {
  if (dragging || draggingGroup) { event.preventDefault(); endDrag(); }
});

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

// --- how long, and whether it is running unattended (EZvac 1.13) ---------------------------------------

let skewMs = 0; // the server's clock minus this browser's, from the last snapshot

/** "under a minute", "12 min", "2 h 5 min", "1 d 3 h". */
function spanText(ms) {
  const minutes = Math.floor(Math.max(0, ms) / 60000);
  if (minutes < 1) return "under a minute";
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ${minutes % 60} min`;
  return `${Math.floor(hours / 24)} d ${hours % 24} h`;
}

/** "11:44PM", with the date first when it wasn't today ("Nov 15 11:44PM"). */
function clockText(ms) {
  const when = new Date(ms);
  const hours = when.getHours();
  const time = `${hours % 12 || 12}:${String(when.getMinutes()).padStart(2, "0")}${hours < 12 ? "AM" : "PM"}`;
  if (when.toDateString() === new Date().toDateString()) return time;
  return `${when.toLocaleDateString([], { month: "short", day: "numeric" })} ${time}`;
}

const MODE_WORD = { heating: "Heating", cooling: "Cooling", idle: "Idle" };

/** "Heating for 12 min", counting up: null when EZvac doesn't say how long (before Pilot 8) or it is off. */
function modeLine(t) {
  if (!t.enabled || !Number.isFinite(t.modeSince)) return null;
  const line = el("div", "mode-line");
  const now = el("span", "mode-for");
  now.dataset.since = String(t.modeSince);
  now.dataset.word = MODE_WORD[t.mode] || cap(String(t.mode));
  now.textContent = `${now.dataset.word} for ${spanText(Date.now() + skewMs - t.modeSince)}`;
  line.appendChild(now);
  return line;
}

/**
 * The log of a thermostat's last finished heating and cooling runs, newest first, each as
 * "heated for 3 min - 11:44PM": how long it ran, and when it stopped. Null when the server sends no log.
 */
function runLog(t) {
  if (!Array.isArray(t.runs)) return null;
  const box = el("section", "run-log");
  box.setAttribute("aria-label", "Log of " + deviceName(t));
  box.appendChild(el("div", "kind", "Log"));
  if (t.runs.length === 0) {
    box.appendChild(el("p", "run-empty", "No heating or cooling runs recorded yet."));
    return box;
  }
  const list = el("ul", "run-list");
  for (const run of t.runs) {
    const word = run.mode === "heating" ? "heated" : "cooled";
    list.appendChild(el("li", "run run-" + run.mode, `${word} for ${spanText(run.endedAt - run.startedAt)} - ${clockText(run.endedAt)}`));
  }
  box.appendChild(list);
  return box;
}

/** Keeps the "for 12 min" text current between snapshots: the page doesn't redraw for a clock. */
function tickModeTimes() {
  for (const node of document.querySelectorAll("[data-since]")) {
    node.textContent = `${node.dataset.word} for ${spanText(Date.now() + skewMs - Number(node.dataset.since))}`;
  }
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
  if (d.kind === "car") {
    // A vehicle: which one it is, and where (or that it is parked in a chunk which isn't loaded)
    parts.push(d.vehicleName && d.vehicleName !== d.label ? `${d.vehicleName} (${d.license})` : d.license);
    parts.push(d.loaded ? `${d.world} ${d.x}, ${d.y}, ${d.z}` : "not loaded");
  } else parts.push(`${d.world} ${d.x}, ${d.y}, ${d.z}`);
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
  if (!fullscreen) side.appendChild(dragGrip(d));
  const place = !fullscreen && arranging ? neighbors.get(d.key) : null;
  if (place) {
    const earlier = arrowButton("Move " + title + " earlier", "◀", () => reorderDevice(d, place.earlier), !place.canEarlier);
    const later = arrowButton("Move " + title + " later", "▶", () => reorderDevice(d, place.later), !place.canLater);
    side.append(earlier, later);
  }
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

/** A small button with an arrow in it, for the reorder mode. */
function arrowButton(label, glyph, onClick, disabled) {
  const button = el("button", "edit arrow", glyph);
  button.type = "button";
  button.title = label;
  button.setAttribute("aria-label", label);
  button.disabled = !!disabled;
  button.addEventListener("click", onClick);
  return button;
}

/** Every organization, folder and home in tree order, labelled with its path. */
function placeChoices() {
  const choices = [];
  const walk = (nodes) => {
    for (const node of nodes) {
      choices.push({ value: node.id, label: pathOf(node.id) });
      walk(node.children);
    }
  };
  walk(tree.roots);
  return choices;
}

/** The "In" menu of a card, or null when the player has made no groups. */
function homePicker(d) {
  if (tree.nodes.size === 0) return null;
  const label = el("label", "home-pick");
  label.append("In ");
  const select = el("select");
  select.setAttribute("aria-label", "Where to keep " + deviceName(d));
  select.appendChild(new Option("No home", ""));
  // Named with where it is ("Acme Co / Kyleton / House 1") so two homes of one name can be told apart.
  for (const choice of placeChoices()) select.appendChild(new Option(choice.label, choice.value));
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

/**
 * The cards of some devices: the dials in one grid, the tiles in another below it. Each grid is in the
 * order its owner arranged the devices (the order they claimed them until they do), and records where the
 * "earlier" and "later" buttons of Arrange mode put each device.
 */
function deviceGrids(list, homes) {
  const box = document.createDocumentFragment();
  for (const [className, members] of [
    ["grid", list.filter((d) => d.kind === "thermostat")],
    ["grid tiles", list.filter((d) => d.kind !== "thermostat")],
  ]) {
    if (!members.length) continue;
    const grid = el("div", className);
    members.forEach((d, i) => {
      neighbors.set(d.key, {
        canEarlier: i > 0,
        earlier: i > 0 ? members[i - 1].key : "",
        canLater: i < members.length - 1,
        later: i + 2 < members.length ? members[i + 2].key : "", // "" is the end of the home
      });
      const card = deviceCard(d, homes);
      acceptOnCard(card, d);
      grid.appendChild(card);
    });
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

/**
 * Sorts the snapshot's organizations, folders and homes into a tree, and the devices into their homes. The
 * server sends each kind in the order its owner arranged it; a group whose parent is missing goes to the top.
 */
function buildTree(state) {
  const nodes = new Map();
  for (const [kind, list] of [["org", state.orgs], ["folder", state.folders], ["home", state.homes]]) {
    for (const g of list || []) nodes.set(g.id, { kind, id: g.id, name: g.name, parent: g.parent || "", children: [] });
  }
  const roots = [];
  for (const node of nodes.values()) {
    const holder = nodes.get(node.parent);
    if (holder && holder.kind !== "home" && node.kind !== "org") holder.children.push(node);
    else { node.parent = ""; roots.push(node); }
  }
  const byHome = new Map();
  const loose = [];
  // A device is in an organization, folder or home (byHome has the devices of each, by group id).
  for (const device of state.devices) {
    const place = nodes.get(device.home);
    if (place) {
      if (!byHome.has(place.id)) byHome.set(place.id, []);
      byHome.get(place.id).push(device);
    } else {
      loose.push(device);
    }
  }
  return { nodes, roots, byHome, loose };
}

/** How many devices are in a group, counting what is inside the groups in it. */
function deviceCount(node) {
  const own = (tree.byHome.get(node.id) || []).length;
  return own + node.children.reduce((sum, child) => sum + deviceCount(child), 0);
}

/** Folders before homes, each in the order its owner arranged them. */
function orderedChildren(node) {
  return [...node.children.filter((c) => c.kind === "folder"), ...node.children.filter((c) => c.kind === "home")];
}

/**
 * An organization, folder or home, with what is in it: folders and homes inside the first two, devices inside a
 * home. `node` is null for the devices that are in no home. The arrow folds it away, and the choice is
 * remembered in this browser.
 */
function groupSection(node, homes, depth) {
  const kind = node ? node.kind : "none";
  const id = node ? node.id : NO_HOME;
  const name = node ? node.name : "No home";
  const group = el("section", "group group-" + kind + (depth > 0 ? " nested" : ""));
  const head = el("div", "group-head");
  const titles = el("div", "group-title");
  const content = el("div", "group-body");

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
  if (node) titles.append(groupGrip(node, group, head));
  titles.append(toggle);
  if (node) titles.appendChild(el("span", "group-kind", GROUP_TITLE[kind]));
  titles.append(heading);
  const count = node ? deviceCount(node) : tree.loose.length;
  titles.appendChild(el("span", "group-meta", `${count} device${count === 1 ? "" : "s"}`));
  head.appendChild(titles);

  if (node) {
    const actions = el("div", "group-actions");
    if (arranging) {
      const siblings = siblingsOf(node);
      const at = siblings.indexOf(node);
      actions.append(
        arrowButton("Move " + name + " up", "▲", () => nudgeGroup(node, -1), at <= 0),
        arrowButton("Move " + name + " down", "▼", () => nudgeGroup(node, 1), at >= siblings.length - 1));
    }
    actions.appendChild(iconButton("Rename " + GROUP_NOUN[kind] + " " + name, () => renameGroup(node, head)));
    if (kind !== "org") {
      const move = el("button", "link small-link", "Move");
      move.type = "button";
      move.title = "Move " + name + " into an organization or folder";
      move.addEventListener("click", () => startMove(node, head));
      actions.appendChild(move);
    }
    const del = el("button", "link small-link", "Delete");
    del.type = "button";
    del.addEventListener("click", () => deleteGroup(node));
    actions.appendChild(del);
    head.appendChild(actions);
  }

  // Devices first (an organization or folder can hold some of its own), then the groups inside.
  const kids = kind === "org" || kind === "folder" ? orderedChildren(node) : [];
  const list = node ? tree.byHome.get(node.id) || [] : tree.loose;
  if (list.length) content.appendChild(deviceGrids(list, homes));
  for (const child of kids) content.appendChild(groupSection(child, homes, depth + 1));
  if (list.length === 0 && kids.length === 0) {
    content.appendChild(el("p", "home-empty", kind === "none"
      ? "Nothing here yet."
      : `Nothing in this ${GROUP_NOUN[kind]} yet. Drag a device here, or use the In menu on a device.`));
  }
  // A device or group can be dropped on the title, or on any part of the group that isn't a card.
  acceptOnGroup(head, group, id === NO_HOME ? "" : id, node, true);
  acceptOnGroup(content, group, id === NO_HOME ? "" : id, node, false);
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
  tree = buildTree(state);
  neighbors.clear();
  // Forget folded groups that no longer exist.
  const before = collapsedHomes.size;
  for (const id of [...collapsedHomes]) if (id !== NO_HOME && !tree.nodes.has(id)) collapsedHomes.delete(id);
  if (collapsedHomes.size !== before) saveCollapsed();
  $("arrange").setAttribute("aria-pressed", String(arranging));
  $("arrange").textContent = arranging ? "Done" : "Arrange";
  const groups = $("groups");
  groups.replaceChildren();
  if (tree.nodes.size === 0) {
    if (state.devices.length) groups.appendChild(deviceGrids(state.devices, homes));
  } else {
    // Organizations first, then folders, then homes at the top; the devices in no home come last.
    const top = [...tree.roots.filter((n) => n.kind === "org"), ...tree.roots.filter((n) => n.kind === "folder"),
      ...tree.roots.filter((n) => n.kind === "home")];
    for (const node of top) groups.appendChild(groupSection(node, homes, 0));
    if (tree.loose.length) groups.appendChild(groupSection(null, homes, 0));
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
    // Not while a name is typed, a dial is being turned, a device is being dragged or a menu is open: redrawing
    // would replace what the hand is holding, and an open menu would snap shut.
    if (editing || changing.size > 0 || dragging || draggingGroup || Date.now() < pickingUntil) return;
    // Don't rebuild the page when nothing changed: it keeps focus on a button being used.
    const s = d.state;
    if (Number.isFinite(s.generatedAt)) skewMs = s.generatedAt - Date.now();
    const key = JSON.stringify([s.devices, s.worlds, s.orgs, s.folders, s.homes, s.stations, d.player]);
    if (key !== lastKey) { lastKey = key; render(d); }
    else stamp(s);
  } catch (e) {
    $("foot").textContent = "Can't reach the server. Trying again…";
  }
}

// A running device changes every second, and the page is redrawn when it does, which would close a menu
// someone is choosing from. A menu can't be asked whether it is open, so the page waits while one has been
// touched in the last ten seconds, and stops waiting the moment a choice is made or the menu loses focus.
const MENU_HOLD_MS = 10000;
const isMenu = (event) => event.target && event.target.tagName === "SELECT";
for (const type of ["focusin", "pointerdown", "keydown"]) {
  document.addEventListener(type, (event) => { if (isMenu(event)) pickingUntil = Date.now() + MENU_HOLD_MS; });
}
for (const type of ["focusout", "change"]) {
  document.addEventListener(type, (event) => { if (isMenu(event)) pickingUntil = 0; });
}

$("detail-close").addEventListener("click", closeDetail);
document.addEventListener("keydown", (event) => {
  // Escape inside a rename form cancels the rename, not the full-screen view behind it.
  if (event.key === "Escape" && detail && !editing && !(event.target.closest && event.target.closest("form.rename"))) closeDetail();
});
acceptOnGroup($("drop-out"), $("drop-out"), "", null, false);
$("new-org").addEventListener("click", () => startNew("org"));
$("new-folder").addEventListener("click", () => startNew("folder"));
$("new-home").addEventListener("click", () => startNew("home"));
$("arrange").addEventListener("click", () => {
  arranging = !arranging;
  if (data) { lastKey = ""; render(data); }
});
$("tab-devices").addEventListener("click", () => showTab("devices"));
$("tab-weather").addEventListener("click", () => showTab("weather"));
$("wx-search").addEventListener("input", (event) => { wxSearch = event.target.value; drawWeather(); });
$("wx-world").addEventListener("change", (event) => { wxWorld = event.target.value; drawWeather(); });
for (const button of document.querySelectorAll("#wx-status button")) {
  button.addEventListener("click", () => { wxStatus = button.dataset.status; drawWeather(); });
}
setInterval(loadWeather, WX_POLL_MS);
setInterval(tickModeTimes, 20000);

// Sign in with a password, with the 8 digit QuickLogin code from /ezstats quicklogin, or set a new
// password ("reset") with the 8 digit code from /ezstats resetpassword.
let loginMode = "password";

function setLoginMode(mode) {
  loginMode = mode === "quick" || mode === "reset" ? mode : "password";
  const quick = loginMode === "quick";
  const reset = loginMode === "reset";
  for (const button of document.querySelectorAll("#login-mode button")) {
    button.setAttribute("aria-pressed", String(button.dataset.mode === loginMode));
  }
  $("login-title").textContent = reset ? "Reset password" : "Sign in";
  $("login-mode").hidden = reset;
  $("field-password").hidden = quick || reset;
  $("field-code").hidden = !(quick || reset);
  $("field-new").hidden = !reset;
  $("code-label").textContent = reset ? "Reset code" : "QuickLogin code";
  $("hint-password").hidden = loginMode !== "password";
  $("hint-code").hidden = !quick;
  $("hint-reset").hidden = !reset;
  $("password").required = loginMode === "password";
  $("code").required = quick || reset;
  $("new-password").required = reset;
  $("new-password2").required = reset;
  $("login-submit").textContent = reset ? "Set new password" : "Sign in";
  $("login-switch").textContent = reset ? "Back to sign in" : "Forgot password?";
  $("login-error").hidden = true;
  (reset || quick ? $("code") : $("password")).focus();
}
for (const button of document.querySelectorAll("#login-mode button")) {
  button.addEventListener("click", () => setLoginMode(button.dataset.mode));
}
$("login-switch").addEventListener("click", () => setLoginMode(loginMode === "reset" ? "password" : "reset"));
// Shown as "1234 5678" while it is typed; only digits are kept.
$("code").addEventListener("input", (event) => {
  const digits = event.target.value.replace(/\D/g, "").slice(0, 8);
  event.target.value = digits.length > 4 ? digits.slice(0, 4) + " " + digits.slice(4) : digits;
});

$("login").addEventListener("submit", async (event) => {
  event.preventDefault();
  const error = $("login-error");
  const submit = $("login-submit");
  error.hidden = true;
  submit.disabled = true;
  try {
    if (loginMode === "reset") {
      const password = $("new-password").value;
      if (password !== $("new-password2").value) throw new Error("The two passwords are different.");
      await post("/api/reset-password", { username: $("username").value.trim(), code: $("code").value, password });
      $("code").value = "";
      $("new-password").value = "";
      $("new-password2").value = "";
      setLoginMode("password");
    } else if (loginMode === "quick") {
      await post("/api/quicklogin", { username: $("username").value.trim(), code: $("code").value });
      $("code").value = "";
    } else {
      await post("/api/login", { username: $("username").value.trim(), password: $("password").value });
      $("password").value = "";
    }
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
