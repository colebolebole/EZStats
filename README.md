# EZStats

A web dashboard for [EZvac](https://github.com/EZvac-Plugin/EZvac) thermostats. Players make a
website account in game, claim the thermostats that are theirs, and sign in to see them live:
room and target temperature, mode, humidity, running equipment and what needs attention. Each
thermostat also shows the weather and seven-day forecast from its nearest weather station, and
players can sort their thermostats into folders. A Weather tab shows every weather station and its
forecast to all signed-in players.

**Controlling thermostats.** On EZvac BETA 1.0 Pilot 7 or newer (API 1.12), owners can change their
thermostats from the website: the target, on and off, and the profile. See
[Controlling thermostats](#controlling-thermostats). On an older EZvac, or with control switched
off, the website is read-only and says why.

## Requirements

- Paper (or a fork) 1.21.x, Java 21
- EZvac BETA 1.0 Pilot 3 or newer (API 1.8). EZStats says so and stays off on an older one.
  Controlling thermostats from the website needs Pilot 7 or newer (API 1.12); on Pilot 3 to 6 the
  website works but is read-only.

## Install

1. Put `EZStats-0.6.0.jar` in `plugins/` next to EZvac.
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
6. Change a thermostat: use the **-** and **+** beside its target, the **Switch off** button, or the
   **Eco / Normal / Turbo** profile.

Don't reuse a password from anywhere else: commands you type can appear in the server's console
and logs.

## Controlling thermostats

Signed in, an owner can change their own thermostats from the website:

- **Target:** the **-** and **+** step it by one degree in the server's units. EZvac keeps it within
  the thermostat's range (a pool thermostat's is wider) and the page says when it did.
- **Power:** **Switch off** and **Switch on** the thermostat's whole system.
- **Profile:** **Eco**, **Normal** or **Turbo** for its system.

How it works, and what to know:

- **Through EZvac's control API.** Each change acts for the signed-in player, so EZvac shows them as
  "Last changed by" in `/hvac info`, and EZvac fires its cancellable `DeviceControlEvent` first: a
  claims plugin that cancels it still stops a change from the website, and the page shows its message.
- **Only the owner** can change a thermostat. EZvac itself checks nothing, so EZStats does: the website
  refuses a thermostat that isn't yours before EZvac is asked.
- **Claiming is first come, first served,** and anyone with `ezstats.use` can claim a thermostat they
  can see. In game, EZvac already lets anyone with `ezvac.use` click a thermostat's sign to change it,
  so this adds no new power over a thermostat, only that an owner can use it **from anywhere**, not just
  standing at the sign. On a server where that matters, an admin can release a claim with
  `/ezstats disown`, a claims plugin can cancel `DeviceControlEvent`, or `control.enabled: false` in
  `config.yml` makes the website read-only (restart to apply).
- **Older EZvac:** below API 1.12, or with `control.enabled: false`, the website is read-only and shows
  the reason. Everything else works.

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

### The Weather tab

The **Weather** tab lists every weather station on the server, organised so a long list stays easy
to scan:

- **Near your thermostats** comes first: the stations that are the nearest for your own thermostats,
  marked with their names.
- **The other stations** follow, grouped by world. Each card shows the outside temperature, today's
  sky, high and low, the station's wind, air quality and lightning strikes, and the seven days side by
  side (sky, high, low, chance of rain). **Day-by-day details** opens each day's wind, AQI and notes.
- **Not forecasting** collects the stations that can't forecast, one line each with the reason:
  switched off by an admin, tower damaged, or not measured yet.
- **Search, world and status filters** along the top narrow the list: search by station or world
  name, show one world, or show only forecasting or only not-forecasting stations.

Unlike thermostats, which belong to one player, stations are the server's, so **any signed-in player**
sees all of them, with their coordinates. Visitors who aren't signed in see nothing. To leave the tab
off, set `weather-tab.enabled: false` in `config.yml`.

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

## For developers

`ThermostatControl` is how the website changes a thermostat. `EzvacThermostatControl` does it through
EZvac's `EzvacControl`; `ReadOnlyControl` refuses with a reason. `Controls.create` picks one, and keeps
the control API's types out of the plugin class so an older EZvac still loads EZStats. The web layer
checks ownership before it calls either.
## Building

```
mvn package
```

The jar is `target/EZStats-0.6.0.jar`. EZvac's API comes from JitPack and is `provided`: never
shade it in.
