# Contributing to FlipWeather

## Building

```bash
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build needs the Android SDK (`compileSdk 34`), pointed at by `sdk.dir` in
`local.properties` or `ANDROID_HOME`. An optional `MAPBOX_TOKEN=pk....` line in
`local.properties` switches the radar's base maps to Mapbox.

## Publishing a release

Installed copies update themselves from this repo's GitHub Releases
(`UpdateChecker.kt`, the same scheme as Flip Launcher). The main screens check
`https://api.github.com/repos/Ben-Showalter/FlipWeather/releases/latest` about
once a week and ask whether to install a newer version. Settings -> Advanced ->
Check for Updates does the same check on demand. Releases are published by
hand. No CI is involved, and the signing key never leaves your machine.

### One-time: create the release signing key

```bash
keytool -genkeypair -v -keystore ~/flipweather-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias flipweather
```

Then create `keystore.properties` at the repo root. It's gitignored, so never
commit it:

```properties
storeFile=/absolute/path/to/flipweather-release.jks
storePassword=...
keyAlias=flipweather
keyPassword=...
```

**Back up the `.jks` file and its passwords.** Android installs an update only
if it's signed with the same key as the installed app. If you lose the key,
every user has to uninstall and reinstall. For the same reason, a phone running
a debug build (such as the old `FlipWeatherAPK.zip`, signed with the debug key)
has to uninstall it once and install a release APK before in-app updates work
on it. Uninstalling clears the saved location and settings.

### Each release

1. In `app/build.gradle`, raise `versionCode` by 1 and set `versionName`,
   e.g. `"1.1"`. Then commit.
2. Build the signed APK:
   ```bash
   gradle :app:assembleRelease
   # -> app/build/outputs/apk/release/app-release.apk
   ```
3. On GitHub, go to **Releases -> Draft a new release**. Create the tag
   `v<versionName>` (e.g. `v1.1`), attach `app-release.apk`, and click
   **Publish release**. Don't mark it as a draft or pre-release, because
   `releases/latest` skips those. The app compares that tag with its own
   `versionName`.

The repo has to stay public: the app reads releases without logging in.
