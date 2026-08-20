
# FlipWeather

A weather app for the Kyocera DuraXV Extreme (E4810). Package is
`com.flipweather.app`. (Was briefly "TurboWeather" - renamed back.)

## Screens & keypad navigation

- **D-pad LEFT/RIGHT** shift between Current / Daily / Radar, in that
  order - clamped at the ends, not wraparound. From Hourly, Town
  Search, or Settings, LEFT jumps straight to Current and RIGHT
  straight to Radar.
- **D-pad CENTER** jumps to Daily Forecast from anywhere (a focused
  list row gets first crack at it for its own "open this" action).
- **Left softkey** = **Refresh** - re-fetches the current screen's
  data, rate-limited per source (see "Refresh throttling" below), and
  re-polls GPS first if your saved location came from GPS (see
  "Caching & GPS" below).
- **Right softkey** = **Options** - opens Settings from anywhere.
  Both labels render in orange on every screen.

Screens:
- **Current Weather** (launcher) - latest observed conditions from the
  nearest NWS ground station, shown as a single dark card (temp, icon,
  description, feels-like, wind, humidity, dewpoint, pressure,
  visibility) - styled to match Daily's look rather than the old mix
  of accent colors.
- **Daily Forecast** - 16-day list, NWS for the first ~7 days blended
  with Open-Meteo for the rest. Each row: icon, day, condition text,
  high/low, precip chance.
- **Hourly** - hour-by-hour breakdown for one day, same NWS/Open-Meteo
  blend.
- **Radar** - pannable/zoomable/animated NEXRAD composite reflectivity
  (NOAA data via Iowa Environmental Mesonet), MapLibre rendering over
  OpenFreeMap's "Liberty" base style. D-pad LEFT/RIGHT/UP/DOWN pan,
  `*`/`#` zoom out/in, `5` re-centers, `8` plays/pauses the animation
  loop, `7`/`9` step one frame back/forward (pausing playback).

## Radar animation

7 frames spanning the last 30 minutes (IEM supports a time-offset
suffix on the same tile URL, e.g. `...-m15m` for 15 minutes ago), all
pre-loaded as separate raster sources/layers stacked on the map at
once. The animation loop only ever changes each frame layer's
`raster-opacity` paint property on a timer (~500ms/frame, with a
1.5s pause on "Now" before looping) - it never touches the base map's
sources, layers, or camera, so streets/labels stay fully loaded and
undisturbed the whole time the loop is running. Refresh rebuilds all
7 frame layers (so the offsets re-resolve against the current time)
but still only touches the radar layers, not the base map.
- **Settings** - location (GPS or town search) plus a "Radar Color
  Legend" button (the old Radar Options screen, now reachable from
  here instead of Radar's right softkey, since that's Options
  everywhere now).
- **Search for a Town** - Open-Meteo geocoding, opened from Settings.

## Caching & GPS

Current and Daily cache their last successful fetch to disk
(SharedPreferences, as JSON). Reopening either screen - including a
cold app restart - shows that cached data immediately, with no network
call. A fetch only happens on: the very first launch (nothing cached
yet), a location change, or pressing the Left softkey (Refresh).

`Prefs.isLocationFromGps` tracks whether your saved location came from
GPS or a searched town. When it's GPS-sourced, pressing Refresh first
gets a new GPS fix (updating the saved lat/lon) before re-fetching
weather for wherever that fix lands - see
`FlipBaseActivity.refreshLocationIfGpsThenRun()`. A searched-town
location just re-fetches for the same saved coordinates.

## Refresh throttling

None of NWS, Open-Meteo, or the IEM radar tiles publish a hard rate
limit for light personal use - `RefreshThrottle.kt` enforces courtesy
intervals matched to how often each source's underlying data actually
changes: Current 10 min, Daily/Hourly 15 min, Radar 5 min. Pressing
Refresh before that shows "already up to date" instead of re-hitting
the network.

## Weather icons

`WeatherIcons.kt` maps both Open-Meteo's WMO codes and NWS's
icon-URL condition codes (with a shortForecast-text keyword fallback)
to a small bundled vector icon set. The Current screen displays NWS's
own hosted icon image when available, but falls back to this local
set (via `WeatherIcons.drawableForShortForecast`) whenever that image
fails to load or NWS has nothing rendered for the condition - this was
the fix for occasional missing icons (fog/haze variants were the most
common case).

## Before you build

Set a contact email in the `USER_AGENT` constant in `NwsApiClient.kt`
(and the header in `CurrentActivity.kt`'s icon loader) - NWS asks
automated clients to identify themselves.

**Upgrading from a TurboWeather install:** the SharedPreferences file
name and package both changed back (`turboweather_prefs` →
`flipweather_prefs`, `com.turboweather.app` → `com.flipweather.app`),
so this installs as a fresh app rather than updating over TurboWeather
- you'll set your location again once via Settings.

## Data sources

- Current conditions: NWS station observations
- Daily + hourly forecast (days ~8-16) + town search: Open-Meteo
  (free, no key)
- Radar base map: OpenFreeMap "Liberty" (free, no key, unlimited use)
- Radar reflectivity: NOAA/NWS NEXRAD composite via Iowa Environmental
  Mesonet (free, no key). `LibreWxrClient.kt` is kept in the project,
  unused, as a documented self-hosted alternative - see the comment at
  the top of that file.

## Known rough edges to watch for on-device

- `minSdk` is 24 (required by MapLibre) - still well under the
  device's Android 9 (API 28).
- The row "card" focus highlight relies on `drawSelectorOnTop="true"`
  drawing a translucent overlay above each row rather than the row's
  own background reacting to focus state.
