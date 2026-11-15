# EZStats

A web dashboard for [EZvac](https://github.com/EZvac-Plugin/EZvac) thermostats. Players sign in
from game with `/ezstats link`, and the website shows every thermostat live: room and target
temperature, mode, humidity, running equipment, what needs attention, plus each world's season
and outdoor weather.

**Read-only for now.** EZvac's public API (through 1.11) can only read; it has no call to set a
target or switch a system on or off. EZStats is built so that changing thermostats from the
website is one class away (see [Adding writes](#adding-writes)); until EZvac's API grows write
methods, changes are made in game and the dashboard says so.

## Requirements

- Paper (or a fork) 1.21.x, Java 21
- EZvac BETA 1.0 Pilot 2 or newer (API 1.7)

## Install

1. Put `EZStats-0.1.0.jar` in `plugins/` next to EZvac.
2. Start the server once, then edit `plugins/EZStats/config.yml`: set `web.public-url` to the
   address players open, and make sure the port (default `8765`) is reachable.
3. `/ezstats reload`.

## Commands

| Command | Permission | Does |
| --- | --- | --- |
| `/ezstats link` | `ezstats.use` (everyone) | Gives you a one-time link that signs your browser in as your Minecraft account |
| `/ezstats unlink` | `ezstats.use` | Signs every browser of yours out |
| `/ezstats status` | `ezstats.use` | The website's address, whether changes are on, who is linked (admins) |
| `/ezstats reload` | `ezstats.admin` (ops) | Reads `config.yml` again and restarts the website |

`/ezs` works too.

## How linking works

`/ezstats link` makes a random 256-bit token that opens once and expires (5 minutes by default).
Opening it sets a `HttpOnly`, `SameSite=Strict` cookie; the browser stays signed in for
`session.ttl-hours`. Tokens and sessions live in memory, so a restart signs everyone out.

The site is plain HTTP unless you put it behind a TLS reverse proxy (then set `web.public-url` to
the `https://` address and `web.bind` to `127.0.0.1`). Over plain HTTP the session cookie crosses
the network unencrypted: use a proxy for anything public.

## What is shown

Every thermostat in every world, as EZvac's own `/hvac list` does. EZvac's API docs ask addons to
mind this: these are every player's builds. Use `hidden-worlds` in `config.yml` to keep a world off
the site.

## Adding writes

`ThermostatControl` is the seam. The web layer already has `POST /api/thermostat/target` and
`/enabled`, and the dashboard draws its buttons whenever `writable()` is true. When EZvac's API can
change thermostats, implement `ThermostatControl` against it (hopping to the main thread) and
swap it for `ReadOnlyControl` in `EZStatsPlugin.onEnable`.

## Building

```
mvn package
```

The jar is `target/EZStats-0.1.0.jar`. EZvac's API comes from JitPack and is `provided`: never
shade it in.
