"use strict";

// Everything that came from a player (a nickname, a thermostat's label, a world's name) is put on the
// page with textContent, never as HTML.

const POLL_MS = 2000;
const MAX_NICKNAME = 32;
const $ = (id) => document.getElementById(id);

const ATTENTION = {
  no_units: "No equipment linked",
  no_vents: "No vents linked",
  units_off: "Equipment switched off",
  cant_keep_up: "Can't keep up",
  undersized: "Undersized",
};
const MODES = { cooling: "Cooling", heating: "Heating", idle: "Idle" };

let units = "F";
let writable = false;
let lastKey = "";
let editing = false; // while a nickname is being typed the page is left alone
let pending = null;  // the newest data that arrived meanwhile

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

const show = (f) => (units === "C" ? (f - 32) * 5 / 9 : f);
const fmt = (f) => (Number.isFinite(f) ? show(f).toFixed(1) : "–");

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

// --- renaming ---------------------------------------------------------------------------------

function startRename(t, titles) {
  editing = true;
  const form = el("form", "rename");
  const input = el("input");
  input.type = "text";
  input.maxLength = MAX_NICKNAME;
  input.value = t.nickname || "";
  input.placeholder = t.label;
  input.setAttribute("aria-label", "Nickname for " + t.label);
  const error = el("p", "error");
  error.hidden = true;

  const actions = el("div", "rename-actions");
  const save = el("button", "small solid", "Save");
  save.type = "submit";
  const cancel = el("button", "small", "Cancel");
  cancel.type = "button";
  actions.append(save, cancel);
  form.append(input, actions, el("span", "rename-hint", "Leave it empty to use the sign's name."), error);

  const finish = () => {
    editing = false;
    if (pending) { const data = pending; pending = null; lastKey = ""; render(data); }
    else { lastKey = ""; refresh(); }
  };
  cancel.addEventListener("click", finish);
  form.addEventListener("keydown", (event) => { if (event.key === "Escape") finish(); });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    save.disabled = true;
    error.hidden = true;
    try {
      await post("/api/thermostat/nickname", { key: t.key, nickname: input.value });
      pending = null;
      finish();
    } catch (e) {
      error.textContent = e.message;
      error.hidden = false;
      save.disabled = false;
    }
  });

  titles.replaceChildren(form);
  input.focus();
  input.select();
}

// --- cards ------------------------------------------------------------------------------------

function card(t) {
  const node = el("article", "card thermo " + (t.enabled ? t.mode : "off"));
  const title = t.nickname || t.label;

  const head = el("div", "head");
  const titles = el("div", "titles");
  const row = el("div", "name-row");
  row.appendChild(el("h3", "name", title));
  const edit = el("button", "edit");
  edit.type = "button";
  edit.title = "Rename";
  edit.setAttribute("aria-label", "Rename " + title);
  edit.appendChild(pencilIcon());
  edit.addEventListener("click", () => startRename(t, titles));
  row.appendChild(edit);
  titles.appendChild(row);

  const where = [];
  if (t.nickname) where.push(t.label);
  where.push(`${t.x}, ${t.y}, ${t.z}`);
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
  if (t.profile && t.profile !== "normal") stat("Profile", t.profile[0].toUpperCase() + t.profile.slice(1));
  node.appendChild(stats);

  if (t.attention && t.attention.length) {
    const chips = el("div", "chips");
    for (const item of t.attention) chips.appendChild(el("span", "chip", ATTENTION[item] || item));
    node.appendChild(chips);
  }

  if (writable) {
    const power = el("button", "small", t.enabled ? "Switch off" : "Switch on");
    power.type = "button";
    power.addEventListener("click", async () => {
      try { await post("/api/thermostat/enabled", { key: t.key, enabled: !t.enabled }); lastKey = ""; refresh(); }
      catch (e) { toast(e.message); }
    });
    node.appendChild(power);
  }
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
    const name = w.season.name[0].toUpperCase() + w.season.name.slice(1);
    parts.push(`${name}, day ${w.season.day} of ${w.season.days}${w.season.paused ? " (paused)" : ""}`);
  }
  if (w.outdoor) {
    let outside = `${fmt(w.outdoor.airF)}°${units} outside`;
    if (w.outdoor.weather) outside += `, ${w.outdoor.weather.toLowerCase()}`;
    parts.push(outside);
  }
  return parts.join(" · ");
}

function render(data) {
  const state = data.state;
  units = state.units === "C" ? "C" : "F";
  writable = state.writable === true;

  $("unlinked").hidden = true;
  $("linked").hidden = false;
  $("who").hidden = false;
  $("who-name").textContent = data.player;
  $("readonly").hidden = writable;

  const groups = $("groups");
  groups.replaceChildren();
  const worlds = new Map(state.worlds.map((w) => [w.name, w]));
  const byWorld = new Map();
  for (const t of state.thermostats) {
    if (!byWorld.has(t.world)) byWorld.set(t.world, []);
    byWorld.get(t.world).push(t);
  }
  for (const [name, list] of byWorld) {
    const group = el("section", "group");
    const head = el("div", "group-head");
    head.appendChild(el("h2", "", name));
    const meta = worlds.has(name) ? worldMeta(worlds.get(name)) : "";
    if (meta) head.appendChild(el("span", "group-meta", meta));
    group.appendChild(head);
    const grid = el("div", "grid");
    for (const t of list) grid.appendChild(card(t));
    group.appendChild(grid);
    groups.appendChild(group);
  }
  $("empty").hidden = state.thermostats.length > 0;
  stamp(state);
}

function stamp(state) {
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
    const data = await response.json();
    if (editing) { pending = data; return; }
    // Don't rebuild the page when nothing changed: it keeps focus on a button being used.
    const key = JSON.stringify(data.state.thermostats) + JSON.stringify(data.state.worlds) + data.player;
    if (key !== lastKey) { lastKey = key; render(data); }
    else stamp(data.state);
  } catch (e) {
    $("foot").textContent = "Can't reach the server. Trying again…";
  }
}

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
  pending = null;
  renderUnlinked();
});

refresh();
setInterval(refresh, POLL_MS);
