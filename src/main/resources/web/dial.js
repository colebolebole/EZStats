"use strict";

// The thermostat card, drawn like a Nest: a ring of ticks with the target on it. Drag the ring, press the
// arrow keys, or use the - and + buttons; the target is sent a moment after the last change, so a drag or a
// run of clicks is one request. Shares the helpers of app.js (el, act, deviceHead...).

const DIAL = { cx: 100, cy: 100, r: 86, start: 135, sweep: 270, ticks: 72 };
const SVG_NS = "http://www.w3.org/2000/svg";
const SEND_AFTER_MS = 450;

/** Keys of what is being changed right now: the page isn't redrawn under a hand that is still moving. */
const changing = new Set();

const polar = (degrees, radius) => [
  DIAL.cx + radius * Math.cos(degrees * Math.PI / 180),
  DIAL.cy + radius * Math.sin(degrees * Math.PI / 180),
];

/** The range a thermostat's target can be set within, in degrees F (EZvac keeps it in range anyway). */
const dialRange = (t) => (t.pool ? [75, 104] : [60, 85]);

/** Snaps a target to the page's step (1 °F, or 0.5 °C) and keeps it in range. Degrees F in and out. */
function snapTarget(t, f) {
  const [min, max] = dialRange(t);
  const step = units === "C" ? 0.5 : 1;
  const shown = Math.round(show(f) / step) * step;
  const back = Math.round((units === "C" ? shown * 9 / 5 + 32 : shown) * 10) / 10;
  return Math.min(max, Math.max(min, back));
}

/** A target as the page shows it: whole degrees in F, a decimal in C. */
function targetText(f) {
  const v = show(f);
  if (units === "C") return v.toFixed(1);
  return Math.abs(v - Math.round(v)) < 0.05 ? String(Math.round(v)) : v.toFixed(1);
}

function svgEl(tag, attrs) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [name, value] of Object.entries(attrs || {})) node.setAttribute(name, value);
  return node;
}

function markLine(node, degrees, from, to) {
  const [x1, y1] = polar(degrees, from);
  const [x2, y2] = polar(degrees, to);
  node.setAttribute("x1", x1.toFixed(2));
  node.setAttribute("y1", y1.toFixed(2));
  node.setAttribute("x2", x2.toFixed(2));
  node.setAttribute("y2", y2.toFixed(2));
}

/**
 * The dial itself. `interactive` makes it draggable; `onTarget(f)` is called with the new target in
 * degrees F once the hand has rested, and returns a promise. Returns the element and a `nudge(direction)`.
 */
function makeDial(t, interactive, onTarget) {
  const [min, max] = dialRange(t);
  const tone = t.enabled ? t.mode : "off";
  const box = el("div", "dial dial-" + tone + (interactive ? " live" : ""));

  const svg = svgEl("svg", { viewBox: "0 0 200 200" });
  svg.setAttribute("aria-hidden", interactive ? "false" : "true");
  const ticks = [];
  for (let i = 0; i < DIAL.ticks; i++) {
    const tick = svgEl("line", { class: "tick" });
    const angle = DIAL.start + (i / (DIAL.ticks - 1)) * DIAL.sweep;
    markLine(tick, angle, DIAL.r - 9, DIAL.r);
    svg.appendChild(tick);
    ticks.push(tick);
  }
  const roomMark = svgEl("line", { class: "mark-room" });
  const targetMark = svgEl("line", { class: "mark-target" });
  svg.append(roomMark, targetMark);
  box.appendChild(svg);

  const center = el("div", "dial-center");
  const label = el("div", "dial-label");
  const number = el("div", "dial-number");
  const now = el("div", "dial-now");
  center.append(label, number, now);
  box.appendChild(center);

  let target = t.targetF;
  const fToAngle = (f) => DIAL.start + ((Math.min(max, Math.max(min, f)) - min) / (max - min)) * DIAL.sweep;

  function draw() {
    const lo = Math.min(t.roomF, target);
    const hi = Math.max(t.roomF, target);
    ticks.forEach((tick, i) => {
      const f = min + (i / (DIAL.ticks - 1)) * (max - min);
      tick.setAttribute("class", t.enabled && f >= lo - 0.2 && f <= hi + 0.2 ? "tick on" : "tick");
    });
    markLine(roomMark, fToAngle(t.roomF), DIAL.r - 15, DIAL.r + 2);
    markLine(targetMark, fToAngle(target), DIAL.r - 18, DIAL.r + 5);
    label.textContent = !t.enabled ? "Off" : t.mode === "cooling" ? "Cooling to" : t.mode === "heating" ? "Heating to" : "Set to";
    number.textContent = targetText(target) + "°";
    now.textContent = `${t.pool ? "Water" : "Now"} ${fmt(t.roomF)}°${units}`;
    if (interactive) {
      svg.setAttribute("aria-valuenow", targetText(target));
      svg.setAttribute("aria-valuetext", `${targetText(target)} degrees ${units}`);
    }
  }
  draw();
  if (!interactive) return { el: box, nudge() {} };

  svg.setAttribute("role", "slider");
  svg.setAttribute("tabindex", "0");
  svg.setAttribute("aria-label", "Target temperature of " + deviceName(t));
  svg.setAttribute("aria-valuemin", targetText(min));
  svg.setAttribute("aria-valuemax", targetText(max));

  let timer = null;
  function send() {
    timer = null;
    if (Math.abs(target - t.targetF) < 0.01) { changing.delete(t.key); return; }
    t.targetF = target;
    onTarget(target).finally(() => changing.delete(t.key));
  }
  function setTarget(f, now) {
    const snapped = snapTarget(t, f);
    if (snapped === target && timer === null) return;
    target = snapped;
    changing.add(t.key);
    draw();
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(send, now ? 0 : SEND_AFTER_MS);
  }
  function nudge(direction) {
    const stepF = units === "C" ? 0.5 * 9 / 5 : 1;
    setTarget(target + direction * stepF);
  }

  // Dragging the ring: the angle of the pointer is the temperature.
  let dragging = false;
  function pointTo(event) {
    const rect = svg.getBoundingClientRect();
    const dx = event.clientX - (rect.left + rect.width / 2);
    const dy = event.clientY - (rect.top + rect.height / 2);
    let degrees = Math.atan2(dy, dx) * 180 / Math.PI;
    degrees = (degrees + 360) % 360;
    let along = (degrees - DIAL.start + 360) % 360;
    if (along > DIAL.sweep) along = along - DIAL.sweep < (360 - DIAL.sweep) / 2 ? DIAL.sweep : 0; // the gap: nearest end
    setTarget(min + (along / DIAL.sweep) * (max - min));
  }
  svg.addEventListener("pointerdown", (event) => {
    const rect = svg.getBoundingClientRect();
    const dist = Math.hypot(event.clientX - (rect.left + rect.width / 2), event.clientY - (rect.top + rect.height / 2));
    const radius = (rect.width / 200) * DIAL.r;
    if (dist < radius * 0.55 || dist > radius * 1.2) return; // the middle belongs to the number, not the ring
    dragging = true;
    svg.setPointerCapture(event.pointerId);
    svg.focus({ preventScroll: true });
    pointTo(event);
    event.preventDefault();
  });
  svg.addEventListener("pointermove", (event) => { if (dragging) pointTo(event); });
  const release = () => {
    if (!dragging) return;
    dragging = false;
    if (timer !== null) { clearTimeout(timer); send(); }
  };
  svg.addEventListener("pointerup", release);
  svg.addEventListener("pointercancel", release);
  svg.addEventListener("keydown", (event) => {
    const up = event.key === "ArrowUp" || event.key === "ArrowRight";
    const down = event.key === "ArrowDown" || event.key === "ArrowLeft";
    if (!up && !down) return;
    event.preventDefault();
    nudge(up ? 1 : -1);
  });
  return { el: box, nudge };
}

function powerIcon() {
  const svg = svgEl("svg", {
    viewBox: "0 0 20 20", fill: "none", stroke: "currentColor", "stroke-width": "1.8",
    "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true",
  });
  svg.append(svgEl("path", { d: "M10 2.5v7" }), svgEl("path", { d: "M5.6 5.1a6.5 6.5 0 1 0 8.8 0" }));
  return svg;
}

function roundButton(label, onClick, content) {
  const button = el("button", "round");
  button.type = "button";
  button.setAttribute("aria-label", label);
  button.title = label;
  if (typeof content === "string") button.textContent = content; else button.appendChild(content);
  button.addEventListener("click", onClick);
  return button;
}

// --- the thermostat card --------------------------------------------------------------------------

function thermostatCard(t, homes) {
  const root = el("article", "card thermo " + (t.enabled ? t.mode : "off"));
  // Full screen puts the dial on the left and everything else beside it.
  const main = fullscreen ? el("div", "fs-main") : root;
  const side = fullscreen ? el("div", "fs-side") : root;
  if (fullscreen) root.append(main, side);
  const title = deviceName(t);
  main.appendChild(deviceHead(t, el("span", "pill " + (t.enabled ? t.mode : ""), t.enabled ? (MODES[t.mode] || t.mode) : "Off")));

  const dial = makeDial(t, writable && t.enabled, (f) => act(t.key, "target", f));
  main.appendChild(dial.el);

  if (writable) {
    const row = el("div", "dial-actions");
    const lower = roundButton("Lower the target of " + title, () => dial.nudge(-1), "−");
    const power = roundButton(t.enabled ? "Switch " + title + " off" : "Switch " + title + " on", () => {
      power.disabled = true;
      act(t.key, "enabled", !t.enabled);
    }, powerIcon());
    power.classList.add("power", t.enabled ? "is-on" : "is-off");
    const raise = roundButton("Raise the target of " + title, () => dial.nudge(1), "+");
    lower.disabled = raise.disabled = !t.enabled;
    row.append(lower, power, raise);
    main.appendChild(row);
  }
  const since = modeLine(t);
  if (since) main.appendChild(since);

  const stats = el("div", "stats");
  const stat = (name, value) => {
    const item = el("span");
    item.append(name + " ", el("b", "", value));
    stats.appendChild(item);
  };
  if (Number.isFinite(t.humidity)) stat("Humidity", Math.round(t.humidity) + "%");
  stat("Equipment", `${t.runningUnits}/${t.totalUnits} running`);
  if (!writable && t.profile && t.profile !== "normal") stat("Profile", cap(t.profile));
  side.appendChild(stats);

  const remote = remoteChip(t);
  if ((t.attention && t.attention.length) || remote) {
    const chips = el("div", "chips");
    if (remote) chips.appendChild(remote);
    for (const item of t.attention || []) chips.appendChild(el("span", "chip", ATTENTION[item] || item));
    side.appendChild(chips);
  }

  if (writable) {
    const row = el("div", "profile-row");
    row.appendChild(el("span", "profile-label", "Profile"));
    row.appendChild(segmented("Profile of " + title, ["eco", "normal", "turbo"], t.profile, (name) => act(t.key, "profile", name)));
    side.appendChild(row);
  }

  const log = runLog(t);
  if (log) side.appendChild(log);

  weatherRow(t, side);

  const picker = homePicker(t, homes);
  if (picker) {
    const foot = el("div", "card-foot");
    foot.appendChild(picker);
    side.appendChild(foot);
  }
  return root;
}
