This app is built to use on the Kyocera E4810 flip phone.

Most of the weather data is coming directly from NOAA, the forcast beyond 7 days, is from Openmeteo. the Radar information is NEXRAD, Iowa Environmental Mesonet. The radar map is drawn without OpenGL (plain Android canvas), so it runs on older flip phones like the E4610 whose GPU drivers crash MapLibre. Its base map is Esri's Dark Gray map with roads by default, or a Light topographic map (every road, plus trails when zoomed in close) chosen under Options - neither needs an API key; to use Mapbox's Dark and Outdoors maps instead, add `MAPBOX_TOKEN=pk....` to `local.properties` before building.

Screens update themselves: the last downloaded data shows instantly, then refreshes in the background when it's due (every 10 min for Current, 15 min for Daily/Hourly). The "Updated" time at the top turns green when fresh and red once it's more than 20 minutes old; a small spinning arrow shows while an update is in progress.

Options (right softkey) → "NWS Forecast Discussion" shows the local NWS office's latest Area Forecast Discussion.
