"use strict";

// Everything that came from a player (a thermostat's label, a world's name) is put on the page with
// textContent, never as HTML.

const POLL_MS = 2000;
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

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function show(f) {
  return units === "C" ? (f - 32) * 5 / 9 : f;
}

function fmt(f, digits = 1) {
  return Number.isFinite(f) ? show(f).toFixed(digits) : "–";
}

function toast(message) {
  const node = el("div", "toast", message);
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

function card(t) {
  const node = el("article", "card" + (t.enabled ? "" : " off"));

  const head = el("div", "card-head");
  const title = el("div");
  title.appendChild(el("div", "label", t.label));
  title.appendChild(el("div", "where", `${t.world} · ${t.x}, ${t.y}, ${t.z}${t.pool ? " · pool" : ""}${t.mini ? " · mini" : ""}`));
  head.appendChild(title);
  head.appendChild(el("span", "badge " + (t.enabled ? t.mode : "idle"), t.enabled ? (MODES[t.mode] || t.mode) : "Off"));
  node.appendChild(head);

  const temps = el("div", "temps");
  const room = el("div");
  const big = el("div", "room", fmt(t.roomF));
  big.appendChild(el("small", "", "°" + units));
  room.appendChild(big);
  room.appendChild(el("div", "caption", t.pool ? "Water now" : "Room now"));
  temps.appendChild(room);

  const target = el("div", "target");
  target.appendChild(el("div", "num", fmt(t.targetF) + "°"));
  target.appendChild(el("div", "caption", "Target"));
  if (writable) {
    const stepper = el("div", "stepper");
    for (const [label, delta] of [["−", -1], ["+", 1]]) {
      const button = el("button", "", label);
      button.type = "button";
      button.setAttribute("aria-label", (delta < 0 ? "Lower" : "Raise") + " the target of " + t.label);
      button.addEventListener("click", () => step(t, delta));
      stepper.appendChild(button);
    }
    target.appendChild(stepper);
  }
  temps.appendChild(target);
  node.appendChild(temps);

  const meta = el("div", "meta");
  if (Number.isFinite(t.humidity)) meta.appendChild(el("span", "", `Humidity ${Math.round(t.humidity)}%`));
  meta.appendChild(el("span", "", `Equipment ${t.runningUnits}/${t.totalUnits} running`));
  if (t.profile && t.profile !== "normal") meta.appendChild(el("span", "", `Profile: ${t.profile}`));
  node.appendChild(meta);

  if (t.attention && t.attention.length) {
    const chips = el("div", "chips");
    for (const item of t.attention) chips.appendChild(el("span", "chip", ATTENTION[item] || item));
    node.appendChild(chips);
  }

  if (writable) {
    const foot = el("div", "card-foot");
    const power = el("button", "", t.enabled ? "Switch off" : "Switch on");
    power.type = "button";
    power.addEventListener("click", async () => {
      try { await post("/api/thermostat/enabled", { key: t.key, enabled: !t.enabled }); refresh(); }
      catch (e) { toast(e.message); }
    });
    foot.appendChild(power);
    node.appendChild(foot);
  }
  return node;
}

// One step is a whole degree in the server's units: 1 °F or 1 °C.
async function step(t, delta) {
  const stepF = units === "C" ? delta * 9 / 5 : delta;
  try {
    await post("/api/thermostat/target", { key: t.key, targetF: t.targetF + stepF });
    refresh();
  } catch (e) {
    toast(e.message);
  }
}

function render(data) {
  const state = data.state;
  units = state.units === "C" ? "C" : "F";
  writable = state.writable === true;

  $("unlinked").hidden = true;
  $("linked").hidden = false;
  $("who").textContent = "Signed in as " + data.player;
  $("logout").hidden = false;

  const notice = $("notice");
  notice.hidden = writable;
  if (!writable) notice.textContent = "Read-only for now: EZvac's API can't change thermostats yet, so changes are made in game.";

  const worlds = $("worlds");
  worlds.replaceChildren();
  for (const w of state.worlds) {
    const box = el("div", "world");
    box.appendChild(el("b", "", w.name));
    if (w.season) {
      box.appendChild(el("span", "", `${w.season.name}, day ${w.season.day} of ${w.season.days}${w.season.paused ? " (paused)" : ""}`));
    }
    if (w.outdoor) {
      const parts = [`${fmt(w.outdoor.airF)}°${units} outside`];
      if (w.outdoor.weather) parts.push(w.outdoor.weather.toLowerCase());
      box.appendChild(el("span", "", parts.join(", ")));
    }
    worlds.appendChild(box);
  }

  const grid = $("grid");
  grid.replaceChildren(...state.thermostats.map(card));
  $("empty").hidden = state.thermostats.length > 0;
  $("foot").textContent = `EZvac ${state.ezvacVersion || ""} · API ${state.apiVersion || ""} · refreshed ${new Date(state.generatedAt).toLocaleTimeString()}`;
}

function renderUnlinked() {
  $("unlinked").hidden = false;
  $("linked").hidden = true;
  $("who").textContent = "";
  $("logout").hidden = true;
  lastKey = "";
}

async function refresh() {
  try {
    const response = await fetch("/api/state", { cache: "no-store" });
    if (response.status === 401) return renderUnlinked();
    if (!response.ok) throw new Error("HTTP " + response.status);
    const data = await response.json();
    // Don't rebuild the page when nothing changed: it keeps focus on a button being used.
    const key = JSON.stringify(data.state.thermostats) + JSON.stringify(data.state.worlds) + data.player;
    if (key !== lastKey) { lastKey = key; render(data); }
    else $("foot").textContent = `EZvac ${data.state.ezvacVersion || ""} · API ${data.state.apiVersion || ""} · refreshed ${new Date(data.state.generatedAt).toLocaleTimeString()}`;
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
  try { await post("/api/logout", {}); } catch (_) { /* signed out or not, show the link page */ }
  lastKey = "";
  renderUnlinked();
});

refresh();
setInterval(refresh, POLL_MS);
