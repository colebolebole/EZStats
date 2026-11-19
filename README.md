# EZStats

A web dashboard for [EZvac](https://github.com/EZvac-Plugin/EZvac). Players make a website account in
game, claim the devices that are theirs, and sign in to see and control them: thermostats, heat pumps,
furnaces, humidifiers and dehumidifiers, air purifiers, fridges and freezers, vents, and every sensor and
monitor. Devices are grouped into **homes** (which can sit in **folders** and **organizations**), and each thermostat is a Nest-style dial. Thermostats also
show the weather and seven-day forecast from their nearest weather station, and a Weather tab shows every
weather station to all signed-in players.

**Controlling devices.** On EZvac BETA 1.0 Pilot 7 or newer (API 1.12), owners can change their devices
from the website. See [Controlling devices](#controlling-devices). On an older EZvac, or with control
switched off, the website is read-only and says why.

## Requirements

- Paper (or a fork) 1.21.x, Java 21
- EZvac BETA 1.0 Pilot 3 or newer (API 1.8). EZStats says so and stays off on an older one.
  Controlling devices from the website needs Pilot 7 or newer (API 1.12); on Pilot 3 to 6 the
  website works but is read-only. Pilot 8 or newer (API 1.13) adds how long a thermostat has been in
  its mode and which devices are running while nobody is near; without it those are simply left out.

## Install

1. Put `EZStats-0.8.0.jar` in `plugins/` next to EZvac.
2. Start the server once, then edit `plugins/EZStats/config.yml`: set `web.public-url` to the
   address players open, and make sure the port (default `8765`) is reachable.
3. `/ezstats reload`.

**QuickLogin replaces the sign-in link.** `/ezstats link` no longer makes a link: it (like `/ezstats
quicklogin`) sends an 8 digit code. The `link.token-ttl-seconds` setting is now `quicklogin.ttl-seconds`;
the old name is still read if the new one is missing.

**Updating from 0.6.0 or earlier:** nothing to do. Your `accounts.yml` is updated on first start: folders
become homes, and what you owned stays yours.

## Getting started (for players)

1. **`/ezstats register <password>`** makes your website account. Your username is your Minecraft
   name. The password is 8 to 128 characters with no spaces.
2. Look at a device (a thermostat's sign, a humidifier's sign, a heat pump's dispenser, a sensor's
   button, a vent...) within 6 blocks and run **`/ezstats own`**. It's now on your account.
   **`/ezstats own system`** claims a thermostat, its equipment, vents, panels and monitors in one go.
3. Open the website, sign in with your Minecraft name and password, and your devices are there. No
   password handy? Run **`/ezstats quicklogin`**, then type the 8 digit code it sends you on the
   website's **QuickLogin** tab.
4. Give a device a nickname: look at it and run **`/ezstats nick Living Room`**, or click the pencil
   next to its name on the website. Nicknames are saved with your account.
5. Group devices on the website: click **New home** (or **New folder** or **New organization**), then drag
   a device by its grip into it, or use the **In** menu on the device.
6. Change a device: drag the dial of a thermostat, or use the buttons and menus on a device's card.

Don't reuse a password from anywhere else: commands you type can appear in the server's console
and logs. To keep a password out of the console altogether, skip `register` and use
**`/ezstats resetpassword`** instead: it gives you a code, and you choose the password on the website.

## Organizations, folders and homes

Your devices are kept in **homes**, and homes can be gathered into **folders** and **organizations**:

```
Acme Co            (organization)
├── Kyleton        (folder)
│   └── House 1    (home)  ← your devices
└── Depot          (folder)
    └── Warehouse  (home)
```

- A **home** is a named group of your devices, like a Nest home: "Main house", "Cabin", "Garage". It
  holds any device, shown together: thermostats first as dials, then the other devices as tiles.
- A **folder** holds homes and other folders, like a town or a street: "Kyleton" → "House 1".
- An **organization** sits at the top and holds folders and homes: make one, then put your folders and homes
  in it. Everything is yours alone: other players don't see your organizations, and an organization is a
  way to arrange your own devices, not a group of players.
- A device can be in **any** of the three: in a home, or straight in a folder or an organization, or in none.

**New organization**, **New folder** and **New home** make one; for a folder or home, **Inside** picks where
it goes. The pencil renames, and **Move** moves a folder or home (with everything in it) into an organization
or folder. **Delete** removes only that group: what was in it isn't deleted, it moves up a level (devices
that were directly in a top-level group go back to "No home").

**Moving a device:** drag it by the **grip** (⋮⋮, at the top right of its card, shown when you point at it):

- drop it **on another device** and it goes just before that one, in that device's place;
- drop it **on the title or an empty part of a home, folder or organization** and it goes to the end of it;
- drop it on the strip that appears at the bottom while you drag, **"Drop here to take it out of its
  group"**, to take it out of every group.

The page waits while you drag. On a touch screen, where dragging doesn't work, use the **In** menu on the
card (it lists every organization, folder and home with its path, like "Acme Co / Kyleton / House 1") and
**Arrange**.

Rules: an organization stays at the top; a folder or home goes in an organization or a folder, never inside
itself, and no more than 5 levels deep. Names are up to 32 characters and unique among what sits side by
side. You can have up to 10 organizations, 40 folders and 20 homes. They are saved in `accounts.yml`, and a
device leaves its group when it is released or removed.

**Changing the order:** drag, or click **Arrange**. Every device gets **◀ ▶** buttons and every
organization, folder and home gets **▲ ▼**: they move it one place earlier or later among the ones beside
it (a device among the devices of its place, dials among dials and tiles among tiles). Click **Done** when
finished. The order is saved. Until you arrange them, devices are in the order you claimed them.

**Folding away:** click the arrow (or the name) at the left of any title to hide what is inside, and
click again to show it. What is folded is remembered in your browser, not on the server.

## Full screen

Thermostats, fridges, freezers and mini fridges (and, on the Weather tab, every forecasting weather
station) have an **open full screen** button next to their name. The view is a bigger version of the
card: a thermostat has a large dial beside its stats, profile and the whole seven-day forecast, a
fridge has larger readings, and a station shows its seven days and day-by-day details at once. Everything
on the card still works there, and it keeps updating. **Back** or **Esc** returns to the page.

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
- **How long:** under the dial, `Cooling for 12 min` says how long the thermostat has been in its mode,
  counting up. This needs EZvac BETA 1.0 Pilot 8 or newer.
- **The log:** below the profile, each thermostat lists its last **5** finished heating and cooling runs,
  newest first, like `heated for 3 min - 11:44PM`: how long it ran, and when it stopped (with the date
  when it wasn't today), in your browser's time. EZvac itself only remembers the latest run, so EZStats
  keeps the rest: it listens for EZvac's mode changes, writes each run down when it ends, and saves them
  in `plugins/EZStats/runs.yml` so a restart keeps them. A thermostat that is removed loses its log. A
  run that was already going when EZStats first started is missed unless EZvac (Pilot 8 or newer) can say
  when it began.

## Running while nobody is near

Since EZvac BETA 1.0 Pilot 8, a house keeps its temperature while its owner is away: thermostats keep
calling, heat pumps and furnaces run, fridges stay cold, and humidifiers, dehumidifiers and purifiers keep
working, even when the chunk isn't loaded. EZvac runs them from what it last saw there, and nothing is
placed or read in an unloaded chunk. You can watch and control such a house from the website as
before. (Readings of thermometers and sensors still need their chunk loaded.)

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
| `/ezstats resetpassword` (or `reset`) | `ezstats.use` | Sends you an 8 digit code in chat; enter it with a new password on the website's **Forgot password?** form. The new password never goes in chat or the console |
| `/ezstats quicklogin` (or `ql`) | `ezstats.use` | **QuickLogin:** sends you an 8 digit one-time code in chat that signs your browser in, no password. `otp`, `code` and the old `link` still work |
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

- **Password:** a password is never stored, only a hash of it: PBKDF2-HMAC-SHA256 with **600,000
  iterations** (OWASP's advice) and a random 16 byte salt for each password, compared in constant time.
  Hashes live in `plugins/EZStats/accounts.yml`, along with who owns what; on Linux and macOS that file
  is readable only by the user running the server. Each hash records its own iteration count, so a hash
  made by an older EZStats (210,000 iterations) still works and is quietly replaced by a stronger one the
  next time its owner signs in. Wrong guesses are limited: 5 per address and username, 20 per address,
  then a 5-minute wait. Passwords are 8 to 128 characters, so nobody can make the server hash a huge one.
  The password is never logged. The one place it can be seen is the server console, if you type it into
  `/ezstats register`: that is what the reset below avoids.
- **Password reset:** `/ezstats resetpassword` sends *you* an 8 digit code in chat. On the website,
  choose **Forgot password?**, type your name, the code and a new password (twice). The new password
  goes straight from your browser to the server, and is never typed into Minecraft, so it can't land in
  the console log. It also works if you never made a password. The code follows the same rules as a
  QuickLogin code (below), but is only good for a reset and a QuickLogin code is only good for signing
  in. A reset signs every browser of yours out and signs in the one that did it.
- **QuickLogin:** `/ezstats quicklogin` sends *you* (in chat, nobody else) an 8 digit code. Type it on
  the website with your Minecraft name and you are signed in, no password. Eight digits are few, so
  it is protected by more than its length:
  - it is random, works **once**, and expires (5 minutes by default, `quicklogin.ttl-seconds`);
  - you have one code at a time: a new one cancels the old, and you can ask once every 10 seconds;
  - after **5 wrong guesses** (from anyone, anywhere) the code is destroyed, so a guess succeeds with a
    chance of at most 5 in 100 million;
  - wrong tries also count against the per-address limit above, and every failure gives the same answer,
    so nobody can tell which players have a code;
  - the code is kept only as a keyed hash in memory, compared in constant time, and is never in the
    console, a log or a URL. The cost to you: the code is shown in your own chat, so don't screenshare it.
  Someone who knows your name could use up your 5 guesses and make you ask for a new code: annoying, not
  a way in.
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

The jar is `target/EZStats-0.8.0.jar`. EZvac's API comes from JitPack and is `provided`: never
shade it in.

## License

EZStats is released under the [MIT License](LICENSE).
