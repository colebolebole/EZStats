# EZStats

A web dashboard for [EZvac](https://github.com/EZvac-Plugin/EZvac). Players make a website account in
game, claim the devices that are theirs, and sign in to see and control them: thermostats, heat pumps,
furnaces, humidifiers and dehumidifiers, air purifiers, fridges and freezers, vents, and every sensor and
monitor. Devices are grouped into **homes**, and each thermostat is a Nest-style dial. Thermostats also
show the weather and seven-day forecast from their nearest weather station, and a Weather tab shows every
weather station to all signed-in players.

**Controlling devices.** On EZvac BETA 1.0 Pilot 7 or newer (API 1.12), owners can change their devices
from the website. See [Controlling devices](#controlling-devices). On an older EZvac, or with control
switched off, the website is read-only and says why.

## Requirements

- Paper (or a fork) 1.21.x, Java 21
- EZvac BETA 1.0 Pilot 3 or newer (API 1.8). EZStats says so and stays off on an older one.
  Controlling devices from the website needs Pilot 7 or newer (API 1.12); on Pilot 3 to 6 the
  website works but is read-only.

## Install

1. Put `EZStats-0.6.1.jar` in `plugins/` next to EZvac.
2. Start the server once, then edit `plugins/EZStats/config.yml`: set `web.public-url` to the
   address players open, and make sure the port (default `8765`) is reachable.
3. `/ezstats reload`.

**Updating from 0.6.0 or earlier:** nothing to do. Your `accounts.yml` is updated on first start: folders
become homes, and what you owned stays yours.

## Getting started (for players)

1. **`/ezstats register <password>`** makes your website account. Your username is your Minecraft
   name. The password is 8 to 128 characters with no spaces.
2. Look at a device (a thermostat's sign, a humidifier's sign, a heat pump's dispenser, a sensor's
   button, a vent...) within 6 blocks and run **`/ezstats own`**. It's now on your account.
   **`/ezstats own system`** claims a thermostat, its equipment, vents, panels and monitors in one go.
3. Open the website, sign in with your Minecraft name and password, and your devices are there.
4. Give a device a nickname: look at it and run **`/ezstats nick Living Room`**, or click the pencil
   next to its name on the website. Nicknames are saved with your account.
5. Group devices into homes on the website: click **New home**, then use the **Home** menu on each device.
6. Change a device: drag the dial of a thermostat, or use the buttons and menus on a device's card.

Don't reuse a password from anywhere else: commands you type can appear in the server's console
and logs.

## Homes

A home is a named group of your devices, like a Nest home: "Main house", "Cabin", "Garage". Make one
with **New home**; the pencil renames it and **Delete** removes it (its devices aren't deleted, they go
back to "No home"). A device's **Home** menu moves it. A home can hold any device, and the devices of
a home are shown together: thermostats first as dials, then the other devices as tiles.

Homes are yours alone. Names are up to 32 characters and unique among your homes, and you can have up
to 20. They are saved in `accounts.yml`, and a device leaves its home when it is released or removed.

## The thermostat dial

Each thermostat is drawn like a Nest: a ring of ticks with the target on it, the room's temperature as
a marker, and the ticks between them lit in blue (cooling) or orange (heating).

- **Drag the ring** to set the target, or click the **-** and **+** under it, or use the arrow keys
  with the dial focused. The number changes at once, and the target is sent a moment after you stop, so
  a drag or a run of clicks is one change.
- **The power button** switches the thermostat's whole system on or off.
- **Eco / Normal / Turbo** sets the profile of its system.
- The range is 60 to 85 degrees F for a room and 75 to 104 for a pool (EZvac keeps the target in
  range). Steps are one degree, or half a degree Celsius, in the server's units.

## Every device

| Device | Shows | Controls |
| --- | --- | --- |
| Thermostat | Dial, humidity, equipment running, what needs attention, nearest weather | Target, power, profile |
| Heat pump, AC, furnace, boiler, pool heat pump | Mode, motor rpm, stage, its system | Switch on and off |
| Dehumidifier, humidifier | Humidity, target, the dehumidifier's tank | Power, target (% RH) |
| Air purifier | Air quality, filter, fan | Power, fan (Low, Medium, High, Auto) |
| Fridge, freezer, mini fridge, refrigerator | Inside temperatures, targets, door, frost | Power, targets, defrost, auto-defrost |
| Thermometer, hygrometer, sensors, air and wind monitors | What they read now | none |
| Sensor hub, RPM monitor, settings panel | What they show, their system | none |
| Vent | Air or water, its system | none |

Readings of instruments are taken from the spot they stand on, and only while its chunk is loaded.

## Controlling devices

Signed in, an owner can change their own devices from the website, through the table above. EZvac
keeps targets within range or rounds them to a device's presets, and the page says when it did.

How it works, and what to know:

- **Through EZvac's control API.** Each change acts for the signed-in player, so EZvac shows them as
  "Last changed by" in `/hvac info`, and EZvac fires its cancellable `DeviceControlEvent` first: a
  claims plugin that cancels it still stops a change from the website, and the page shows its message.
- **Only the owner** can change a device. EZvac itself checks nothing, so EZStats does: the website
  refuses a device that isn't yours before EZvac is asked.
- **Claiming is first come, first served,** and anyone with `ezstats.use` can claim a device they
  can see. In game, EZvac already lets anyone with `ezvac.use` use a device's sign or screen, so this
  adds no new power over a device, only that an owner can use it **from anywhere**, not just standing
  at it. On a server where that matters, an admin can release a claim with `/ezstats disown`, a claims
  plugin can cancel `DeviceControlEvent`, or `control.enabled: false` in `config.yml` makes the website
  read-only (restart to apply).
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

## Commands

| Command | Permission | Does |
| --- | --- | --- |
| `/ezstats register <password>` | `ezstats.use` (everyone) | Makes your website account and sets its password |
| `/ezstats password <new>` | `ezstats.use` | Changes your password and signs every browser out |
| `/ezstats own` | `ezstats.use` | Claims the device you're looking at: any thermostat, equipment, humidifier, sensor... |
| `/ezstats own system` | `ezstats.use` | Claims the device you're looking at and every device of its system: the thermostat, its equipment, vents, panels and monitors |
| `/ezstats nick [nickname]` (or `rename`) | `ezstats.use` | Names the device you're looking at, up to 32 characters, on your EZStats page. With no nickname it goes back to its own name. The sign itself is unchanged: EZvac's API can't rename, and `/hvac rename` does that |
| `/ezstats disown` | `ezstats.use` | Lets go of the device you're looking at. Admins can free anyone's |
| `/ezstats link` | `ezstats.use` | A one-time link that signs your browser in, no password |
| `/ezstats unlink` | `ezstats.use` | Signs every browser of yours out |
| `/ezstats status` | `ezstats.use` | The website's address, your password and devices, who is signed in (admins) |
| `/ezstats reload` | `ezstats.admin` (ops) | Reads `config.yml` again and restarts the website |

`/ezs` works too.

## Ownership

A device belongs to the first player who runs `/ezstats own` on it, and the website shows each
player only their own. EZvac has no owner of its own, so EZStats can't tell who built a device:
on a server where that matters, an admin can free a wrongly claimed one with `/ezstats disown`.
Ownership ends when the device is broken or removed, so a new one built on the same block
starts unowned, and the nickname and home go with it. A nickname belongs to the owner: nobody else sees it.

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

The website's JSON API, with every route, request and response and a curl example, is documented in [docs/api.md](docs/api.md).

`DeviceControl` is how the website changes a device. `EzvacDeviceControl` does it through
EZvac's `EzvacControl`; `ReadOnlyControl` refuses with a reason. `Controls.create` picks one, and keeps
the control API's types out of the plugin class so an older EZvac still loads EZStats. The web layer
checks ownership before it calls either.
## Building

```
mvn package
```

The jar is `target/EZStats-0.6.1.jar`. EZvac's API comes from JitPack and is `provided`: never
shade it in.

## License

EZStats is released under the [MIT License](LICENSE).
