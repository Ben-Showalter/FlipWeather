This app is built to use on the Kyocera E4810 flip phone.

Most of the weather data is coming directly from NOAA, the forcast beyond 7 days, is from Openmeteo. the Radar information is NEXRAD, Iowa Environmental Mesonet. The radar map is drawn without OpenGL (plain Android canvas), so it runs on older flip phones like the E4610 whose GPU drivers crash MapLibre. Its base map is a Light topographic map by default (every road, plus trails when zoomed in close - the radar hides at that zoom), or Esri's Dark Gray map with roads, chosen from Options on the Radar screen - neither needs an API key; to use Mapbox's Dark and Outdoors maps instead, add `MAPBOX_TOKEN=pk....` to `local.properties` before building.

Screens update themselves: the last downloaded data shows instantly, then refreshes in the background when it's due (every 10 min for Current, 15 min for Daily/Hourly, 5 min for Radar), so there is no Refresh key. The "Updated" time at the top turns green when fresh and red once it's more than 20 minutes old; a small spinning arrow shows while an update is in progress. On Radar the red time only appears while the map is still loading.

Options (right softkey) → "NWS Forecast Discussion" shows the local NWS office's latest Area Forecast Discussion.

Options opens with the right softkey, or the dedicated Options key on Sonim phones. Settings > Advanced can move it to the left softkey.

The phone's own white softkey-label bar at the bottom is hidden, since every screen draws its own labels.
