# The EZStats HTTP API

EZStats runs a small web server (`web.port` in `config.yml`, `8765` by default). Its dashboard is a
single-page app that talks to a JSON API on the same server. This page documents that API, for anyone
building something else on it: a command-line tool, a Discord bot, a status screen.

> **Not versioned.** There is no `/v1`. The dashboard is the API's one guaranteed client, so a release
> can change it. Pin the EZStats version you test against, and check the
> [release notes](https://github.com/colebolebole/EZStats/releases) when you update.

The code is in [`WebServer.java`](../src/main/java/com/ezstats/web/WebServer.java); what the JSON contains
is built in [`SnapshotService.java`](../src/main/java/com/ezstats/snapshot/SnapshotService.java) and
[`DeviceJson.java`](../src/main/java/com/ezstats/snapshot/DeviceJson.java).

- [Conventions](#conventions)
- [Signing in](#signing-in): [`POST /api/quicklogin`](#post-apiquicklogin), [`POST /api/reset-password`](#post-apireset-password), [`POST /api/login`](#post-apilogin), [`POST /api/logout`](#post-apilogout)
- [Reading](#reading): [`GET /api/state`](#get-apistate), [`GET /api/weather`](#get-apiweather)
- [Changing devices](#changing-devices): [`POST /api/device/control`](#post-apidevicecontrol)
- [Your own data](#your-own-data): [nicknames](#post-apidevicenickname), [organizations, folders and homes](#organizations-folders-and-homes)
- [Static files](#static-files)
- [Errors and status codes](#errors-and-status-codes)
- [Limits and security](#limits-and-security)
- [A complete example](#a-complete-example)

## Conventions

- **Base URL:** the address players open, `web.public-url` in `config.yml`, for example
  `https://stats.example.com`. Paths below are relative to it.
- **Bodies are JSON.** Every `POST` takes a JSON object of at most **4096 bytes** (UTF-8) and answers with
  JSON. `Content-Type: application/json` is customary; the server doesn't check it.
- **Signed in with a cookie.** A successful sign-in sets `ezstats_session`; send it back on every call.
  It is `HttpOnly` and `SameSite=Strict`, plus `Secure` when `web.public-url` starts with `https://`.
  A client that isn't a browser keeps it in a cookie jar (`curl -c` and `-b`).
- **Every `POST` needs the header `X-EZStats`** (any value). A web page on another site can't add a custom
  header without a preflight, which the server never grants, so this stops cross-site requests.
  Without it a `POST` is refused with `403`, even when signed in.
- **Temperatures are degrees Fahrenheit** in every field and every request, whatever the server's `units`.
  The `units` field (`"F"` or `"C"`) says what a player is shown: convert for display, not for sending.
- **Numbers that aren't known are left out** of the JSON (a pool has no `humidity`, say) rather than sent
  as `null`. A field marked "optional" below may be absent.
- **A device is named by its `key`:** `<world-uuid>:<x>:<y>:<z>`, the block of its sign, button or
  dispenser, for example `7f1c...-....:12:64:-30`. Read keys from [`GET /api/state`](#get-apistate); don't
  build them.
- **Errors** are `{"error": "A sentence fit to show a player."}` with an HTTP status; see
  [Errors and status codes](#errors-and-status-codes).
- **No CORS.** The server sends no CORS headers, so a web page on another origin can't call it. Anything
  that isn't a browser page can.

## Signing in

A player gets in one of two ways, and both end with the same session cookie.

### `POST /api/quicklogin`

**QuickLogin:** signs in with a Minecraft name and the eight digit one-time code that `/ezstats quicklogin`
sent the player in chat. No password needed.

```json
{ "username": "Steve", "code": "1234 5678" }
```

- `username` is the Minecraft name, any case, at most 32 characters. `code` is eight digits; spaces and
  dashes are ignored (`"1234 5678"`, `"1234-5678"` and `"12345678"` are the same).
- **`200`** `{"ok":true}` with `Set-Cookie: ezstats_session=...`.
- **`401`** `{"error":"That code didn't work. Run /ezstats quicklogin in game for a new one."}`. The same
  answer whether the name has no code, the code is wrong, used or expired.
- **`429`** after too many wrong tries; see [Limits and security](#limits-and-security).
- **`400`** for a missing field, or a code that isn't eight digits.

A code works once, for `quicklogin.ttl-seconds` (5 minutes by default), and is destroyed after five wrong
guesses. The old `GET /link` route, which spent a link in a URL, is gone.

### `POST /api/reset-password`

Sets a new password with a Minecraft name and the eight digit code that `/ezstats resetpassword` sent the
player in chat, so the password is typed into the website and never into Minecraft. It also works for a
player who has no password yet.

```json
{ "username": "Steve", "code": "1234 5678", "password": "a-new-password" }
```

- `password` is 8 to 128 characters with no whitespace. It is checked **before** the code is spent, so a
  typo doesn't cost the player their code.
- A reset code is good only for this route, and a QuickLogin code only for `/api/quicklogin`.
- **`200`** `{"ok":true}` with `Set-Cookie: ezstats_session=...`. Every other session of the player is ended,
  and the response signs in the caller.
- **`401`** `{"error":"That code didn't work. Run /ezstats resetpassword in game for a new one."}` for any
  failure of the code, the same answer in each case.
- **`429`** after too many wrong tries (shared with sign-in); **`400`** for a missing field, a code that isn't
  eight digits, or a password that is too short, too long or has spaces.

### `POST /api/login`

Signs in with a Minecraft name and the password set with `/ezstats register`.

```json
{ "username": "Steve", "password": "correct-horse-battery" }
```

- `username` is the Minecraft name, any case, at most 32 characters. `password` is at most 128.
- **`200`** `{"ok":true}` with `Set-Cookie: ezstats_session=...`.
- **`401`** `{"error":"Wrong username or password."}`. The same answer, and about the same time, whether
  the name exists or not.
- **`429`** after too many wrong tries; see [Limits and security](#limits-and-security).
- **`400`** for a missing, empty or too long field.

### `POST /api/logout`

Ends the session of the cookie sent, and clears the cookie. Needs the `X-EZStats` header; takes no body.
Always **`200`** `{"ok":true}`.

## Reading

Both calls need the session cookie, and answer **`401`** `{"error":"Not signed in."}` without one.

### `GET /api/state`

Everything the dashboard draws: the signed-in player's own devices and homes, and the weather around them.
Nothing is returned that the player doesn't own, apart from the stations their thermostats use.

```json
{
  "player": "Steve",
  "weatherTab": true,
  "state": {
    "generatedAt": 1794721322987,
    "units": "F",
    "writable": true,
    "ezvacVersion": "BETA-1.0-P7",
    "apiVersion": "1.12",
    "orgs":    [ { "id": "6df481d8", "name": "Acme Co", "parent": "" } ],
    "folders": [ { "id": "10b1597b", "name": "Kyleton", "parent": "6df481d8" } ],
    "homes":   [ { "id": "a1b2c3d4", "name": "House 1", "parent": "10b1597b" } ],
    "devices": [ /* see Devices */ ],
    "worlds":  [ /* see Worlds */ ],
    "stations": { /* see Stations */ }
  }
}
```

| Field | Meaning |
| --- | --- |
| `player` | The signed-in player's Minecraft name. |
| `weatherTab` | Whether [`GET /api/weather`](#get-apiweather) is switched on (`weather-tab.enabled`). |
| `state.generatedAt` | When the server last read EZvac for these devices, in milliseconds since the epoch. It is read every `refresh-ticks` (20 ticks, one second, by default). |
| `state.units` | `"F"` or `"C"`: what the server shows players. All numbers are still °F. |
| `state.writable` | Whether [`POST /api/device/control`](#post-apidevicecontrol) can change anything. |
| `state.readOnlyReason` | Only when `writable` is `false`: why, as a sentence. |
| `state.ezvacVersion`, `state.apiVersion` | EZvac's plugin version and API version. |
| `state.orgs`, `state.folders`, `state.homes` | The player's [organizations, folders and homes](#organizations-folders-and-homes), each list in the order the player arranged it, with the `parent` of each (`""` at the top). `state.homes` is the same list as before, now with `parent`; the other two are new in 0.8. |
| `state.devices` | The player's devices, in the order they arranged them (the order they claimed them until they do). |
| `state.worlds` | The worlds that hold those devices. |
| `state.stations` | The weather stations those thermostats use, by station key. |

#### Devices

Every device has these fields:

| Field | |
| --- | --- |
| `key` | Its name, as described above. |
| `kind` | `thermostat`, `equipment`, `unit`, `vent`, `instrument`, or `other` (a kind this version doesn't know). |
| `type` | What it is within its kind; see the tables below. |
| `label` | Its own name (the thermostat's or sign's label), or `""` for a device that has none. |
| `world`, `x`, `y`, `z` | Its world's name and block. |
| `nickname` | Optional. The name its owner gave it. |
| `home` | The id of the organization, folder or home it is in, or `""`. (It is called `home` from when only homes held devices.) |
| `weather` | Optional, thermostats only: `{ "station": "<key>", "distance": 98 }`, its nearest weather station and how many blocks away. Absent when no working station is in range. |
| `loaded` | Optional, thermostats, equipment and units (not vents or instruments), **EZvac BETA 1.0 Pilot 8 or newer**. `true` when the device's chunk is loaded; `false` when it is running unattended: since Pilot 8 EZvac keeps a house running from what it last saw there while nobody is near. The website doesn't show this; the field is there for other clients. Absent on an older EZvac. |

**`kind: "thermostat"`**, with `type` `room` or `pool`

| Field | |
| --- | --- |
| `roomF`, `targetF` | The room's (or pool water's) temperature, and its target. |
| `mode` | `cooling`, `heating` or `idle`. |
| `enabled` | `false` while its system is switched off. |
| `pool`, `mini` | A pool thermostat; a mini thermostat on a wall button. |
| `humidity` | Optional. The room's relative humidity, percent. |
| `runningUnits`, `totalUnits` | Equipment running, and in its system. |
| `unitsOff` | Every unit that could heat or cool its system is switched off server-wide. |
| `profile` | `eco`, `normal` or `turbo`. |
| `blocked` | Its system can't run: no equipment, no vents, or the units are off. |
| `attention` | What needs a look: `no_units`, `no_vents`, `units_off`, `cant_keep_up`, `undersized`. |
| `modeSince` | Optional, **Pilot 8 or newer**. When it went into its `mode`, as epoch milliseconds in real time; it counts across restarts. It is a time and not a length so the field doesn't change every second: work the duration out against `state.generatedAt`. Absent when not known. |
| `runs` | The thermostat's log: its last 5 finished heating and cooling runs, newest first, as `[{ "mode": "heating", "startedAt": 1794857423619, "endedAt": 1794859223619 }, ...]` (epoch milliseconds; `mode` is `heating` or `cooling`). EZStats keeps these itself, from EZvac's mode-change events, in `runs.yml`; on Pilot 8 or newer EZvac's own `lastRun` fills in a run that ended before EZStats was listening. An empty array when none has been seen. |
| `lastRun` | Optional, **Pilot 8 or newer**. Its last finished heating or cooling run: `{ "mode": "heating", "startedAt": 1794857423619, "endedAt": 1794859223619 }` (epoch milliseconds). Absent before one has ended. |

**`kind: "equipment"`**, with `type` `heat_pump`, `air_conditioner`, `furnace`, `boiler` or `pool_heat_pump`

`enabled`, `running`, `mode` (`cooling`, `heating`, `idle`), `rpm` (optional), `tier` (1 single-stage, 2
two-stage, 3 variable), `system` (the label of its thermostat, or `""`).

**`kind: "unit"`**, with `type` `dehumidifier`, `humidifier`, `air_purifier`, `fridge`, `deep_freezer`,
`refrigerator` or `mini_fridge`

| Field | |
| --- | --- |
| `powered` | `false` when switched off. |
| `state` | `off`, `idle`, `running`, `emptying`, `no_filter` or `filter_spent`. |
| `humidity` | Optional. The humidity of the space it works on, percent (also sent for an air purifier; fridges and freezers have none). |
| `setpoint` | A dehumidifier's or humidifier's target in % RH; an air purifier's `0`; a fridge's or freezer's target in °F (a refrigerator's is its freezer's). |
| `tank` | A dehumidifier's tank, 0 to 1. Present on every unit, and `0` on the others. |
| `filter` | Optional. An air purifier's filter, 1 fresh to 0 full. |
| `air` | Optional. `{ "aqi": 42, "band": "good" }` for the air it works on. |
| `fanMode`, `fanSpeed` | Optional, air purifiers: `low`, `medium`, `high`, `auto`; the speed it runs at is never `auto`. |
| `insideF` | Optional. A fridge's or freezer's inside temperature (a refrigerator's freezer). |
| `cold` | Optional, refrigeration: `doorOpen`, `frost` (0 to 1, optional), `defrosting`, `autoDefrost`, `fridgeInsideF` and `fridgeSetpointF` (a refrigerator's fridge, optional), `chillerF` (a mini fridge's, optional), `barrels`. |

**`kind: "vent"`**, with `type` `air` or `water`: `system`.

**`kind: "instrument"`**, with `type` `thermometer`, `hygrometer`, `thermo_hygrometer`,
`temperature_sensor`, `humidity_sensor`, `temperature_humidity_sensor`, `weather_sensor`, `sensor_hub`,
`rpm_monitor`, `settings_panel`, `air_monitor`, `air_sensor` or `wind_sensor`

`system` (optional, for monitors and panels linked to one), `links` (how many sensors a hub shows, or
units an RPM monitor shows), and `reading`, optional: read from the spot the instrument stands on, and
only while its chunk is loaded. A `reading` holds some of `tempF`, `humidity`, `weather`, `air`
(`{aqi, band}`), `windMs`, `windLevel` and `windFrom`, by what the instrument measures.

`band` is an EPA air quality band: `good`, `moderate`, `unhealthy_for_sensitive_groups`, `unhealthy`,
`very_unhealthy` or `hazardous`.

#### Worlds

```json
{ "name": "world",
  "time": 6000,
  "season":  { "name": "summer", "day": 3, "days": 15, "paused": false },
  "outdoor": { "airF": 88.2, "feelsLikeF": 93.0, "humidity": 40.0, "weather": "Clear" } }
```

`time` is the in-game time of day in the world, in ticks, 0 to 23999 (**new in 0.7.4**): `0` is sunrise (6:00),
`6000` noon, `12000` sunset (18:00) and `18000` midnight. It is read with the rest of the state, every
`refresh-ticks`, so it is as old as `state.generatedAt`. `season` is absent in a world without seasons.
`outdoor` is the climate at the world's spawn.

#### Stations

`state.stations` maps a station's key to the station, for the stations that are the nearest to the player's
thermostats. A station is described under [`GET /api/weather`](#get-apiweather).

### `GET /api/weather`

Every weather station on the server and its forecast, for **any signed-in player**: stations belong to the
server, not to a player. **`404`** when an admin has switched the tab off (`weather-tab.enabled: false`).
Refreshed every `weather-refresh-ticks` (200 ticks, ten seconds, by default).

```json
{ "generatedAt": 1794721322987, "units": "F", "stations": [ /* stations, by world then label */ ] }
```

A station:

| Field | |
| --- | --- |
| `key`, `label`, `world`, `x`, `y`, `z` | Its name and place (the key is the same kind as a device's). |
| `enabled`, `intact`, `working` | Switched on; its tower standing; and so forecasting. When `working` is `false` there is no forecast, and `enabled` and `intact` say why. |
| `worldTime` | **New in 0.7.4.** The in-game time of day in its world, in ticks, 0 to 23999 (`0` sunrise, `6000` noon, `12000` sunset, `18000` midnight), as of the last refresh. Optional: absent when its world is not loaded. |
| `areaRadius` | How many blocks around it it measures. |
| `outdoorF` | The outdoor temperature around it, optional. |
| `lightningStrikes` | How often lightning has struck its tower. |
| `wind` | `{ "speedMs": 5.5, "level": "breezy", "from": "W" }`: metres per second; `calm`, `breezy`, `windy` or `gale`; and the compass point it blows from, optional. |
| `air` | Optional. `{ "aqi": 42, "band": "good" }` over its area now. |
| `forecast` | Seven days, today first. |

A forecast day:

| Field | |
| --- | --- |
| `daysAhead` | `0` for today. |
| `highF`, `lowF` | The day's high and low. |
| `sky` | `clear`, `rain`, `snow` or `thunder`. |
| `rain`, `thunder` | Chances, 0 to 1. |
| `note` | What is certain about the day, such as `"Rain from 15:00"`, or `""`. |
| `aqi` | Optional. The day's expected air quality index. |
| `wind` | Optional. The day's wind, as above. |

## Changing devices

### `POST /api/device/control`

Changes a device through EZvac's control API. Only the player who **owns** the device (claimed it in game
with `/ezstats own`) can: anything else is `403`. The change is made for the signed-in player, so EZvac
records them as "Last changed by", and EZvac's cancellable `DeviceControlEvent` can still refuse it.

```json
{ "key": "7f1c...:12:64:-30", "action": "target", "value": 72 }
```

| `action` | On | `value` | Does |
| --- | --- | --- | --- |
| `target` | thermostat | number, °F | Sets the target. EZvac keeps it in range (60 to 85, pool 75 to 104). |
| `enabled` | thermostat | `true` or `false` | Switches its whole system on or off. |
| `profile` | thermostat | `"eco"`, `"normal"` or `"turbo"` | Sets its system's profile. |
| `powered` | dehumidifier, humidifier, air purifier, fridge, freezer | `true` or `false` | Switches it on or off. |
| `setpoint` | dehumidifier, humidifier, fridge, freezer, refrigerator's freezer | whole number | % RH (to its nearest preset), or °F. |
| `fridge-setpoint` | refrigerator | whole number, °F | The fridge compartment's target. |
| `fan` | air purifier | `"low"`, `"medium"`, `"high"` or `"auto"` | Sets the fan. |
| `defrost` | deep freezer, refrigerator | none | Starts a defrost. |
| `auto-defrost` | refrigerator | `true` or `false` | Whether its freezer defrosts itself. |
| `equipment-enabled` | heat pump, AC, furnace, boiler, pool heat pump | `true` or `false` | Switches it on or off. |

`value` must have the right type: a number is a JSON number (not `"72"`), a switch is a JSON boolean.

**`200`**

```json
{ "ok": true, "adjusted": true, "message": "Kept within its range: 85°F." }
```

`adjusted` is `true` when it was done but not exactly as asked (kept in range, or rounded to a preset),
and `message` then says to what. `message` is EZvac's own sentence, fit to show a player.

Failures carry EZvac's sentence in `error`:

| Status | When |
| --- | --- |
| `400` | A missing or badly typed field, an `action` that isn't in the table, or a device that can't do this (a humidifier has no fan). |
| `403` | The device isn't yours, or a plugin cancelled the change. |
| `404` | There is no device there any more. |
| `501` | The website is read-only: EZvac is too old to control (before API 1.12), or `control.enabled` is `false`. `error` says which. |
| `503`, `504` | EZvac didn't answer within five seconds, or the call was interrupted. Try again. |

The server reads EZvac again on the next tick after a change, so ask [`GET /api/state`](#get-apistate)
a moment later (about 350 ms is enough) for the new values.

## Your own data

These change EZStats' own records, kept in `accounts.yml`, not EZvac. They work even when the website is
read-only. Only your own devices, organizations, folders and homes can be touched.

### `POST /api/device/nickname`

```json
{ "key": "7f1c...:12:64:-30", "nickname": "Living room" }
```

Names a device you own on your page. An empty `nickname` clears it. At most 32 characters, no control
characters; runs of spaces become one. **`200`** `{"ok":true}`; **`400`** if it is too long or has control
characters; **`403`** if the device isn't yours.

### Organizations, folders and homes

Three kinds of group, each with an `id`, a `name` and a `parent` (the id of what it is in, or `""` at the
top):

- an **organization** (up to **10**) sits at the top and holds folders and homes;
- a **folder** (up to **40**) holds folders and homes;
- a **home** (up to **20**) holds devices. A device can also be in an organization or folder directly (its
  `home` field is the id of whichever group it is in).

Names are 1 to 32 characters, no control characters, and unique among what sits side by side (same
`parent`), ignoring case. An organization is only at the top; a folder or home goes in an organization or a
folder, never inside itself or what it holds, and nesting stops at 5 levels (a group's depth is 0 at the
top, at most 4). They are the player's own: nothing is shared with other players.

| Call | Body | Does |
| --- | --- | --- |
| `POST /api/orgs` | `{ "name": "Acme Co" }` | Makes an organization. `200` `{"ok":true,"id":"a1b2c3d4"}`. |
| `POST /api/folders` | `{ "name": "Kyleton", "parent": "<org or folder id>" }` | Makes a folder. `parent` is optional (the top). |
| `POST /api/homes` | `{ "name": "House 1", "parent": "<org or folder id>" }` | Makes a home. `parent` is optional. |
| `POST /api/orgs/rename`, `/api/folders/rename`, `/api/homes/rename` | `{ "id": "a1b2c3d4", "name": "New name" }` | Renames one of that kind. |
| `POST /api/orgs/delete`, `/api/folders/delete`, `/api/homes/delete` | `{ "id": "a1b2c3d4" }` | Deletes it. Nothing inside is deleted: the groups and devices it held move up to where the deleted one was (devices of a top-level group go to no group). |
| `POST /api/groups/move` | `{ "id": "a1b2c3d4", "parent": "<org or folder id>" }` | Moves a folder or home, with what it holds. `"parent": ""` is the top. |
| `POST /api/groups/place` | `{ "id": "a1b2c3d4", "parent": "<org or folder id>", "before": "e5f6a7b8" }` | What dragging a group does, in one call: moves it (with what it holds) into `parent` (`""` for the top) and just before another group there. `"before": ""`, or a group that isn't in `parent`, puts it at the end. The same rules as move: an organization stays at the top, never inside itself or what it holds, 5 levels at most, names unique beside each other (`409`). |
| `POST /api/groups/order` | `{ "id": "a1b2c3d4", "before": "e5f6a7b8" }` | Puts a group just before another in the same place. `"before": ""` is the end. |
| `POST /api/device/home` | `{ "key": "7f1c...:12:64:-30", "home": "a1b2c3d4" }` | Puts a device you own in an organization, folder or home (any group id; the field keeps its old name). `"home": ""` takes it out of any. |
| `POST /api/device/order` | `{ "key": "7f1c...:12:64:-30", "before": "7f1c...:13:64:-30" }` | Puts a device just before another device in the same place (or both in none). `"before": ""` is the end of its place. |
| `POST /api/device/place` | `{ "key": "7f1c...:12:64:-30", "group": "a1b2c3d4", "before": "7f1c...:13:64:-30" }` | What a drag and drop does, in one call: puts a device in a group (`""` for none) and just before another device already there. `"before": ""`, or a device that isn't in that group, puts it at the end. |

A route works only on groups of its kind: `/api/homes/rename` with a folder's id is `404`.

Each answers **`200`** `{"ok":true}` unless noted. Errors: **`400`** for a bad name, or `It can't go there.` (an
organization somewhere but the top, a group inside a home, inside itself, too deep, or a `before` that
isn't beside it); **`404`** `No such home.` (or folder, organization, group); **`409`** for a duplicate name
or more groups of that kind than allowed; **`403`** when the device isn't yours.

The order is the order of each list in [`GET /api/state`](#get-apistate): `orgs`, `folders` and `homes` are
each in the order their owner arranged them, and `devices` in the order they arranged those (the order
they were claimed until then).

## Static files

`GET` only, no sign-in needed: `/` and `/index.html`, `/app.js`, `/dial.js`, `/devices.js` and `/app.css`.
Anything else that isn't an API path is `404`.

## Errors and status codes

| Status | Meaning here |
| --- | --- |
| `200` | Done. |
| `400` | Bad request: a missing, mistyped or unacceptable field. |
| `401` | Not signed in (no cookie, or the session expired or was ended). |
| `403` | A `POST` without `X-EZStats`, a device that isn't yours, or EZvac or a plugin refused. |
| `404` | Unknown path, a home or device that doesn't exist, or the Weather tab switched off. |
| `409` | A home name already used, or the 20-home limit. |
| `413` | A body over 4096 bytes. |
| `429` | Too many wrong passwords or QuickLogin codes. |
| `500` | Something went wrong on the server; it is logged in the console. |
| `501` | The website can't change devices on this server. |
| `503`, `504` | EZvac was interrupted or didn't answer in time. |

## Limits and security

- **Passwords** are never stored, only salted PBKDF2-HMAC-SHA256 hashes (600,000 iterations, a random
  16 byte salt each, constant-time compare) in `accounts.yml`, which on POSIX systems is readable only by the
  server's user. A hash made with fewer iterations by an older release still verifies and is replaced by a
  stronger one at the owner's next sign-in. Passwords are limited to 128 characters.
- **QuickLogin codes** are eight random digits from a `SecureRandom`, held in memory as a keyed hash
  (HMAC-SHA-256, compared in constant time), never the digits. A player has one at a time (asking again
  cancels the first, and no more than once in 10 seconds), it works once, expires, and is **destroyed after
  five wrong guesses from anyone, anywhere**, so a code can be guessed with a chance of at most 5 in 100
  million. Every failure answers the same, so a visitor can't tell which names have a code. The code goes
  only to the player's own chat, never the console, and never into a URL.
- **Wrong passwords and codes are limited.** Five wrong tries for the same address and username, or twenty
  from one address (passwords and codes count together), lock that key for five minutes (tries are counted
  over ten minutes). While locked, even the right password or code gets `429`. Behind a reverse proxy, set `web.trust-forwarded-for: true` so addresses are
  the client's, from `X-Forwarded-For`; otherwise every visitor shares the proxy's address.
- **Sessions** are random 256-bit values kept in memory and last `session.ttl-hours` (72 by default). A
  server restart ends them all; `/ezstats unlink` or `/ezstats password` ends a player's.
- **Plain HTTP sends the password, the QuickLogin code and the cookie in the clear.** Put the site behind a TLS reverse proxy for
  anything public, and set `web.public-url` to the `https://` address so the cookie is `Secure`.
- **Owner only.** A device's owner is the first player to claim it. The server checks it on every change
  and every edit, before EZvac is asked.
- Every response carries `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and a `Content-Security-Policy` that allows only
  the site's own scripts and styles.

## A complete example

Sign in, read the state, set a thermostat, and sign out, with `curl`. In game, `/ezstats register
<password>` and `/ezstats own` (looking at a thermostat) come first.

```bash
BASE=https://stats.example.com
JAR=/tmp/ezstats.cookies

# Sign in; the cookie is kept in the jar.
curl -s -c "$JAR" -X POST "$BASE/api/login" \
  -H 'X-EZStats: 1' -H 'Content-Type: application/json' \
  -d '{"username":"Steve","password":"correct-horse-battery"}'
# {"ok":true}

# Read everything the player owns; pick a thermostat's key out of it.
curl -s -b "$JAR" "$BASE/api/state" | jq '.state.devices[] | select(.kind=="thermostat") | {key, label, roomF, targetF, mode}'

# Set its target to 70 °F.
KEY='7f1c...:12:64:-30'
curl -s -b "$JAR" -X POST "$BASE/api/device/control" \
  -H 'X-EZStats: 1' -H 'Content-Type: application/json' \
  -d "{\"key\":\"$KEY\",\"action\":\"target\",\"value\":70}"
# {"ok":true,"adjusted":false,"message":"Done."}

# Every weather station on the server.
curl -s -b "$JAR" "$BASE/api/weather" | jq '.stations[] | {label, working, outdoorF}'

# Sign out.
curl -s -b "$JAR" -X POST "$BASE/api/logout" -H 'X-EZStats: 1'
```
