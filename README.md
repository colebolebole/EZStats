# EZStats

A web dashboard for [EZvac](https://github.com/EZvac-Plugin/EZvac) thermostats. Players make a
website account in game, claim the thermostats that are theirs, and sign in to see them live:
room and target temperature, mode, humidity, running equipment, what needs attention, plus the
season and outdoor weather of their worlds.

**Read-only for now.** EZvac's public API (through 1.11) can only read; it has no call to set a
target or switch a system on or off. EZStats is built so that changing thermostats from the
website is one class away (see [Adding writes](#adding-writes)); until EZvac's API grows write
methods, changes are made in game and the dashboard says so.

## Requirements

- Paper (or a fork) 1.21.x, Java 21
- EZvac BETA 1.0 Pilot 2 or newer (API 1.7)

## Install

1. Put `EZStats-0.2.0.jar` in `plugins/` next to EZvac.
2. Start the server once, then edit `plugins/EZStats/config.yml`: set `web.public-url` to the
   address players open, and make sure the port (default `8765`) is reachable.
3. `/ezstats reload`.

## Getting started (for players)

1. **`/ezstats register <password>`** makes your website account. Your username is your Minecraft
   name. The password is 8 to 128 characters with no spaces.
2. Look at a thermostat's sign (within 6 blocks) and run **`/ezstats own`**. It's now on your account.
3. Open the website, sign in with your Minecraft name and password, and your thermostats are there.

Don't reuse a password from anywhere else: commands you type can appear in the server's console
and logs.

## Commands

| Command | Permission | Does |
| --- | --- | --- |
| `/ezstats register <password>` | `ezstats.use` (everyone) | Makes your website account and sets its password |
| `/ezstats password <new>` | `ezstats.use` | Changes your password and signs every browser out |
| `/ezstats own` | `ezstats.use` | Claims the thermostat you're looking at |
| `/ezstats disown` | `ezstats.use` | Lets go of the thermostat you're looking at. Admins can free anyone's |
| `/ezstats link` | `ezstats.use` | A one-time link that signs your browser in, no password |
| `/ezstats unlink` | `ezstats.use` | Signs every browser of yours out |
| `/ezstats status` | `ezstats.use` | The website's address, your password and thermostats, who is signed in (admins) |
| `/ezstats reload` | `ezstats.admin` (ops) | Reads `config.yml` again and restarts the website |

`/ezs` works too.

## Ownership

A thermostat belongs to the first player who runs `/ezstats own` on it, and the website shows each
player only their own. EZvac has no owner of its own, so EZStats can't tell who built a thermostat:
on a server where that matters, an admin can free a wrongly claimed one with `/ezstats disown`.
Ownership ends when the thermostat is broken or removed, so a new one built on the same block
starts unowned.

## How sign-in works

- **Password:** passwords are stored as salted PBKDF2-HMAC-SHA256 hashes in
  `plugins/EZStats/accounts.yml`, along with who owns what. Wrong guesses are limited: 5 per
  address and username, 20 per address, then a 5-minute wait.
- **Link:** `/ezstats link` makes a random 256-bit token that opens once and expires (5 minutes by
  default).
- Either way the browser gets a `HttpOnly`, `SameSite=Strict` cookie and stays signed in for
  `session.ttl-hours`. Sessions live in memory, so a restart signs everyone out.

The site is plain HTTP unless you put it behind a TLS reverse proxy (then set `web.public-url` to
the `https://` address, `web.bind` to `127.0.0.1`, and `web.trust-forwarded-for` to `true` if the
proxy adds `X-Forwarded-For`). Over plain HTTP the password and session cookie cross the network
unencrypted: use a proxy for anything public.

## Adding writes

`ThermostatControl` is the seam. The web layer already has `POST /api/thermostat/target` and
`/enabled`, which refuse anyone but the thermostat's owner, and the dashboard draws its buttons
whenever `writable()` is true. When EZvac's API can change thermostats, implement
`ThermostatControl` against it (hopping to the main thread) and swap it for `ReadOnlyControl` in
`EZStatsPlugin.onEnable`.

## Building

```
mvn package
```

The jar is `target/EZStats-0.2.0.jar`. EZvac's API comes from JitPack and is `provided`: never
shade it in.
