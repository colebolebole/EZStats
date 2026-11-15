# EZStats

A web dashboard for [EZvac](https://github.com/EZvac-Plugin/EZvac) thermostats. Players make a
website account in game, claim the thermostats that are theirs, and sign in to see them live:
room and target temperature, mode, humidity, running equipment and what needs attention. Each
thermostat also shows the weather and seven-day forecast from its nearest weather station, and
players can sort their thermostats into folders.

**Read-only for now.** EZvac's public API (through 1.11) can only read; it has no call to set a
target or switch a system on or off. EZStats is built so that changing thermostats from the
website is one class away (see [Adding writes](#adding-writes)); until EZvac's API grows write
methods, changes are made in game and the dashboard says so.

## Requirements

- Paper (or a fork) 1.21.x, Java 21
- EZvac BETA 1.0 Pilot 3 or newer (API 1.8). EZStats says so and stays off on an older one.

## Install

1. Put `EZStats-0.4.0.jar` in `plugins/` next to EZvac.
2. Start the server once, then edit `plugins/EZStats/config.yml`: set `web.public-url` to the
   address players open, and make sure the port (default `8765`) is reachable.
3. `/ezstats reload`.

## Getting started (for players)

1. **`/ezstats register <password>`** makes your website account. Your username is your Minecraft
   name. The password is 8 to 128 characters with no spaces.
2. Look at a thermostat's sign (within 6 blocks) and run **`/ezstats own`**. It's now on your account.
3. Open the website, sign in with your Minecraft name and password, and your thermostats are there.
4. Give a thermostat a nickname: look at it and run **`/ezstats nick Living Room`**, or click the
   pencil next to its name on the website. Nicknames are saved with your account.

5. Sort thermostats into folders on the website: click **New folder**, then use the **Folder** menu on
   each thermostat's card.

Don't reuse a password from anywhere else: commands you type can appear in the server's console
and logs.

## Weather and forecasts

Each thermostat shows what its nearest weather station says: the sky, the temperature outside, today's
high, low and chance of rain. **Forecast** opens the station's seven days with each day's sky, high and
low, chances of rain and thunder, wind and direction, air quality and a note such as "Rain from 15:00",
plus the station's wind and air right now.

- **Nearest station:** found from the thermostat's own coordinates with EZvac's API
  (`weatherStationsNear`), the same stations a player could see with `/hvac forecast`: working ones,
  within about 400 blocks, nearest first. A thermostat with none in range says so.
- **Building one:** weather stations are built by admins in the Overworld. See EZvac's
  [Weather stations](https://github.com/EZvac-Plugin/EZvac/blob/main/docs/weather-stations.md).
- **How often:** every `weather-refresh-ticks` (default 200, ten seconds), and only for thermostats
  somebody owns.
- Temperatures use the server's units (°F or °C), and wind shows in mph or km/h to match.

## Folders

Folders are yours alone, made on the website: **New folder** makes one, the pencil renames it, and
**Delete** removes it (its thermostats aren't deleted, they go back to Unfiled). Each thermostat's
**Folder** menu moves it. Names are up to 32 characters and unique among your folders, and you can have
up to 20. They are saved in `accounts.yml`, and a thermostat leaves its folder when it is released or
removed.

## Commands

| Command | Permission | Does |
| --- | --- | --- |
| `/ezstats register <password>` | `ezstats.use` (everyone) | Makes your website account and sets its password |
| `/ezstats password <new>` | `ezstats.use` | Changes your password and signs every browser out |
| `/ezstats own` | `ezstats.use` | Claims the thermostat you're looking at |
| `/ezstats nick [nickname]` (or `rename`) | `ezstats.use` | Names the thermostat you're looking at, up to 32 characters, on your EZStats page. With no nickname it goes back to the sign's label. The sign itself is unchanged: EZvac's API can't rename, and `/hvac rename` does that |
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
starts unowned, and the nickname goes with it. A nickname belongs to the owner: nobody else sees it.

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

The jar is `target/EZStats-0.4.0.jar`. EZvac's API comes from JitPack and is `provided`: never
shade it in.
