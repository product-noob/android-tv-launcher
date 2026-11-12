# android-tv-launcher

Debloat scripts + a custom Apple TV-style home screen for Android TV / Google TV. No root; everything over ADB on the local network. README.md has the user-facing steps; this file is for coding agents.

## Layout
- `debloat/debloat.sh <tv-ip> [list]` hides packages with `pm uninstall -k --user 0` (reversible). `restore.sh <tv-ip> [pkg]` uses `cmd package install-existing`; with no package it restores the whole list and re-enables `com.google.android.tvlauncher`. Lists: one package per line, `#` comments. `adb shell -n` is required inside the `while read` loops, or adb swallows the rest of the list from stdin.
- `launcher/`: Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material`), `androidx.palette`. AGP 8.10 / Gradle 8.11.1 / Kotlin 2.0.21, JDK 17+, minSdk 28. No other dependencies (HTTP via HttpURLConnection, JSON via org.json).
  - `Apps.kt`: LEANBACK apps, banners, glow colour; favourites in SharedPreferences (`launcher`/`favourites`, seeded from `DEFAULT_FAVOURITES`); recents via UsageStatsManager (screensaver excluded).
  - `HomeScreen.kt`: header, trending hero, favourites (240x135) / Inputs / Recent / All apps shelves (200x112), long-press menu (Move, add/remove favourite, App info). Move mode: left/right, OK or Back finishes; the finishing key-up is swallowed or it launches the app. `OLED_CARE` gates idle/night dimming and pixel shift.
  - `Trending.kt`: TMDB `/trending/{feed}/week`, 8 titles, refreshed every 6h, retried every 15s until the first success. Config from `local.properties` → BuildConfig: `tmdb.key` (v3 key or v4 token), `tmdb.region` (default: device country), `tmdb.feed`. Button = first installed app in `PROVIDER_APPS` that streams it in the region, else `stremio:///detail/{movie|series}/{imdb}`. Backdrops kept as JPEG bytes; only previous/current/next decoded. Dark TMDB logos are rejected in favour of text.
  - `Glass.kt`: fake Liquid Glass (no RenderEffect before API 31). `Modifier.glass(shape)` draws a pre-blurred `GlassSource` lined up via positionInRoot. Home source = `TrendingTitle.frosted` (the backdrop blurred with Backdrop()'s masks baked in; keep `frostedBackdrop()` and `Backdrop()` in sync). Menu source = `captureBlurredWindow()` (PixelCopy at 160px + box blur).
  - `Inputs.kt`: HDMI inputs as `TvApp(isInput = true)`; CEC child inputs give the device name; hidden unless connected. The input with a CEC device (Apple TV) is the home hero (`HdmiHero`), focused on Home; other inputs get the Inputs row.
  - `TvPower.kt`: `TvPowerService` (accessibility service) makes the TV follow the Apple TV. Reads CEC from `logcat -s HDMI` (READ_LOGS): device `<Standby>` while its input is in front → screensaver-on-sleep off + `GLOBAL_ACTION_LOCK_SCREEN`; screen on → `switchToHdmiDevice` (Home first if the Apple TV is asleep, so Live TV can be restarted and send CEC routing); wake request during the OLED refresh → held, replayed when it ends. Polls `sys.screen.turn_on` every 2 s on purpose: it's the only signal for the OLED refresh (panel off while `isInteractive` is true); don't replace it with SCREEN_ON/OFF. `HdmiDevice.asleep` feeds the hero's status. Also `launch`/`launchFresh`. MainActivity's first resume within 3 min of boot opens the Apple TV.
  - `Wallpaper.kt`: `DayPart` images (`res/drawable-nodpi/wallpaper_*.jpg`, made by `tools/wallpapers.py`), decoded only while visible (`MainActivity.visible`), drift via `rememberDriftClock` at 4 fps read in the draw phase. `scrimAlpha()` and `LivingWallpaper`'s gradients must match (glass bakes the scrim into `frostedWallpaper`). `GlassSource` bounds/alpha are lambdas so drift and scroll only redraw.
  - `Hotword.kt`: hands-free "Ok Google" foreground service, off (`HANDS_FREE_OK_GOOGLE`). Not needed on the Xiaomi MiTV: katniss (the system VoiceInteractionService) listens anyway at ~25% of a core; only its own "Ok Google" setting or the mic switch stops that.
  - `Weather.kt`: last network location + Open-Meteo (no key). `Http.kt`: shared GET helpers. `Fonts.kt`: Inter variable font, `UiText` (opsz 14, default) / `UiDisplay` (opsz 32, for 20sp+).
  - `MainActivity.kt`: HOME activity; reloads on package changes; Home re-focuses the first favourite; Back swallowed. `launchFresh` kills the target's background process first (Live TV apps and Stremio ignore new inputs/links while running).

## Design rules (tvOS-like)
No row containers; rows bleed to the screen edge (`EdgePadding` as content padding). Focus = scale + app-colour glow + light rim + glare, never a thick border. Sentence-case type, weight not letter-spacing. Pills are glass; focused = solid white. The backdrop fades and parallaxes as the page scrolls.

## Build & deploy
```
cd launcher && cp local.properties.example local.properties   # sdk.dir + optional tmdb.*
./gradlew assembleRelease
./deploy.sh <tv-ip>      # install + every adb grant (location, usage stats, READ_LOGS, WRITE_SECURE_SETTINGS, SYSTEM_ALERT_WINDOW, accessibility, home)
```
Always ship release (R8); debug Compose is too slow on TV hardware. Everything that runs on the TV must be in git: a power service once lived only in an installed APK and had to be recovered with jadx. A build signed on another machine can't update in place (`--replace-signature` uninstalls first and resets favourites); keep `~/.android/debug.keystore` backed up outside the repo.

## Rules when working on a user's TV
- Connecting: classic network ADB is `<ip>:5555`; TVs with "Wireless debugging" (Google TV, Android 11+) need `adb pair <ip>:<pairing-port>` with the on-screen code, then `adb connect <ip>:<port>`. The scripts accept `<ip>` or `<ip>:<port>`.
- Ask before removing any package. Never remove Live TV, `com.mediatek.*` / other chip services, the remote's Bluetooth service, `com.android.tv.settings`, Play Services, the keyboard or setup wizards. Also not `com.xiaomi.android.tvsetup.partnercustomizer` (its provider missing = `com.mediatek.tv.service` crash loop, HDMI freezes).
- After removing packages, wait a minute and run `adb shell dumpsys dropbox | grep -c system_app_crash`; if a system app is crash-looping, restore whatever it depends on before calling the debloat done.
- Memory check for launcher changes: `dumpsys meminfo me.prince.launcher` native heap must stay flat with the launcher in the background (an old build leaked ~20 MB/min).
- Disable (never uninstall) the stock launcher, and only after the custom launcher is installed and verified, otherwise Home shows a black screen. Fallback: `pm enable com.google.android.tvlauncher`. Warn the user that this can break hands-free "Ok Google" (README → Gotchas).
- Never commit or print the user's TMDB key; it belongs in `local.properties` only.
- Test by screenshotting: `adb shell screencap -p /sdcard/s.png` then `adb pull` (some TVs print vendor log lines into `exec-out` output, corrupting the PNG). Protected video and HDMI inputs capture as black.
- Before sending key events (`input keyevent`), check `dumpsys activity activities | grep mResumedActivity`: if a video app or TV input is in front, someone may be watching.

## Gotchas already handled in code
- Compose for TV pins the focused item 30% from the edge, scrolling the header off-screen; `minimalScroll()` (a custom BringIntoViewSpec) only scrolls when an item would leave the screen.
- On cold start the first tile isn't attached for a few frames; focus is requested with a short retry loop.
- The Wi-Fi name needs ACCESS_COARSE_LOCATION; VPN ad blockers make the VPN the active network, so the label checks all networks.
- The first TLS handshake after start sometimes fails (seen with AdGuard's HTTPS filtering); trending retries every 15s.
