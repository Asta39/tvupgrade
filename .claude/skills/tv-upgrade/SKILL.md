---
name: tv-upgrade
description: Rebuild this repo's result on an Android TV / Google TV — debloat it over ADB, install the forked launcher as Home, push the ian-dusk theme, grant its services, and reproduce or extend every feature (liquid-glass hero clock with depth cut-outs, dock, Now Playing, Control Center, profiles, weather, StandBy with synced lyrics) to the same detail. Use when someone hands you this repo and asks to set up their TV, change the look, add a feature, or debug the launcher.
---

# TV Upgrade playbook

The finished result is in `README.md` and `screenshots/`. Match it; don't redesign it unless the owner asks.
Work through the phases in order. Each phase ends with a check: don't move on until it passes.

## 0. Know the repo

| Path | What it is |
|---|---|
| `debloat/tcl-g10-google-tv.txt` | Packages to disable (TCL G10, Google TV 12). Other TVs need their own list |
| `scripts/debloat.sh <ip>` | Backs up package + settings state to `backup/`, then disables the list |
| `scripts/install-launcher.sh <ip>` | Builds `launcher/` with Gradle and installs it as Home (disables the stock launcher) |
| `scripts/push-theme.sh <ip> [theme]` | Pushes `themes/ian-dusk/` (config, wallpapers, depth cut-outs, avatars) and restarts Home |
| `scripts/grant.sh <ip>` | One-time grants: media sessions, the accessibility service, brightness, DND |
| `scripts/restore.sh <ip>` | Undo everything |
| `scripts/make_cutouts.py` | Reference for making depth cut-outs (needs adapting to paths) |
| `launcher/app/src/main/java/com/gathin/tvhome/` | The launcher (plain Java, no dependencies, minSdk 26) |
| `themes/ian-dusk/config.json` | Every switch of this look. Fork keys stay off unless a theme turns them on |
| `tools/` | cleanyourtv's Python ADB tools (`tv.py`, `theme_restart.py`, …) and its venv |

Launcher files that carry this fork's features:

| Feature | Files |
|---|---|
| Hero clock (liquid glass) + depth layer | `HomeExtras.java` (layout), `GlassClock.java` (rendering), `WallpaperPlayer.java` (`shownBitmap`, `shownView`, `onClock`, `onDepth`, `depthShown`) |
| Theme keys | `Config.java` (`heroClock`, `keepWatching`, `nowPlaying`, `controlCenter`, `weatherCity/Lat/Lon`, `lyricsOffsetMs`) |
| Now Playing card | `NowPlayingCard.java`, `MediaWatch.java` (media sessions via the notification listener `GuardRevival`) |
| Control Center | `ControlCenter.java`, `FrostedLayout.java`, `SleepService.java` (sleep, timer, Night Shift, Screen off) |
| Profiles + avatars | `Profiles.java`, theme `profiles` + `avatars/` |
| Weather | `Weather.java` (Open-Meteo, no key, ≤ every 30 min) |
| StandBy + screensaver | `StandByActivity.java`, `StandByDream.java`, `StandByView.java` (faces incl. `NowPlayingFace`) |
| Lyrics | `Lyrics.java` (LRCLIB lookup), `LyricLine.java` (per-frame sweep) |

## 1. Prerequisites (ask the owner for anything missing)

- TV on the same network. On the TV: Settings → System → About → press Build 7 times; then Developer options →
  **USB debugging** on, and **Network debugging** on if shown. Get the TV's IP.
- Google's platform-tools `adb` on PATH. Use it, not the Python client, for everything here:
  `export TV_ADB_BINARY=adb TV_HOST=<ip>:5555`. The Python client's shell chunks are too long for some TVs.
- Python 3 (scripts create `tools/venv` themselves), **JDK 17 exactly** (a newer JDK fails Gradle with
  "Unsupported class file major version"; Homebrew `openjdk@17` works), Android SDK (Android Studio's, or
  `ANDROID_HOME`).
- `adb connect <ip>:5555`, then the owner accepts the "Allow USB debugging?" prompt **on the TV** (tick Always).
  Check: `adb -s <ip>:5555 shell getprop ro.build.version.sdk` prints a number ≥ 26.

## 2. Debloat (ask first; it changes the TV)

1. Model check: `adb shell getprop ro.product.model`. The list is for a **TCL G10**. Any other model: start
   from that model's list in cleanyourtv's `examples/` and show the owner the list before running.
2. `scripts/debloat.sh <ip>`. It saves `backup/` first. Never `pm uninstall`.
3. Optional, with consent: Private DNS to AdGuard
   (`settings put global private_dns_mode hostname`, `private_dns_specifier dns.adguard-dns.com`).
4. Check: `adb shell pm list packages -d` contains the list; TV still boots to a home screen.

## 3. Launcher

1. `scripts/install-launcher.sh <ip>`. Ends with "Home now resolves to com.gathin.tvhome/.MainActivity".
2. **Every reinstall drops the accessibility service.** Always run right after:
   `adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/.SleepService`
3. `scripts/grant.sh <ip>` once (notification listener for Now Playing, accessibility, WRITE_SETTINGS for
   brightness, DND access).
4. StandBy as the screensaver:
   `adb shell settings put secure screensaver_components com.gathin.tvhome/.StandByDream`
5. Check: press Home; the launcher shows.

## 4. Theme

1. Edit `themes/ian-dusk/config.json` for the owner: `dock_apps` (package names from
   `adb shell pm list packages -3`), `profiles` (name, dock_apps, wallpapers, avatar), `weather_city/lat/lon`,
   `greeting_name`. Leave the layout keys as they are; they make the look.
2. `scripts/push-theme.sh <ip>`. It writes files through the launcher's `ThemeProvider` (`content write`), then
   restarts Home. To change only config, push just `config.json` and a new `theme_revision`, then run
   `tools/theme_restart.py`; re-pushing nine 4K wallpapers each time is slow.
3. Check: screenshot (section 7). Clock, dock, weather pill and gear show; CH+ changes the wallpaper.

### Wallpapers and depth cut-outs

- Wallpapers: exactly 3840×2160 JPGs in `wallpapers/`, named `NN-name.jpg`. Portrait photos rotated, AI-upscaled
  (Magnific Precision 4–8×), cropped to 16:9.
- Depth: `depth/<same name>.png`, same size, the foreground only with alpha. Made by masking the sky (SAM),
  inverting it, and fading the cut-out out above a set height so the **top half of the digits always shows**.
  `make_cutouts.py` has the ramps used.
- Skip cut-outs on busy foliage (forest rays, ferns): the digits look broken, not layered.

## 5. Feature specs (reproduce to these values)

**Hero clock** (`hero_clock: true`): date above (`EEEE d MMMM`, Outfit medium 20sp, 90% white), then the time.
- Font: **Barlow Condensed Medium** (`res/font/clock_barlow.ttf`), **250sp**, `textScaleX` **0.74**, letter-spacing
  −0.02, 12/24 h from the system. Box 44dp from the top, clock −14dp under the date. The stock top-left clock is
  set INVISIBLE (not GONE: the top bar is laid out against it).
- Liquid glass, in `GlassClock`: once a minute (`ACTION_TIME_TICK`) and on each wallpaper change
  (`WallpaperPlayer.onClock`), on a worker thread:
  1. draw the digits into an alpha mask;
  2. blur it twice with a box blur of radius **2.5dp** into a height field (a wider blur makes the whole stroke
     a bevel and it reads as chrome, not glass);
  3. for each digit pixel, sample the picture behind (mapped through the wallpaper ImageView's inverse matrix) at
     an offset along the height field's slope (up to ~10dp at the edges) plus 6% magnification; red and blue
     sampled 22% further / nearer for a slight chromatic split;
  4. lift brightness 5–12% (more at the top); add a bright rim on edges facing the top-left light, a fainter rim
     on the far edges, a little shade between; premultiply and draw the bitmap.
  No frosted blur on the digits: that is the buttons' look, and the owner explicitly rejected it.
- The wallpaper may be a HARDWARE bitmap: copy to ARGB_8888 once per picture before reading pixels.
- RuntimeShader/AGSL needs API 33; this TV is API 31, so the glass is CPU-rendered per minute, never per frame.
- Depth layer: a full-screen `ImageView` added right after the clock box (in front of it, behind everything
  else), cross-fading with the wallpaper (450ms). The clock box fades out when the app grid scrolls down.

**Dock**: 8 icons, `icon_fill` square tiles, iOS 22.5% corners, `columns: 8`, `uniform_tiles`, no title, no
band. Down shows all apps; Up returns. CH+/CH− step wallpapers on Home.

**Now Playing card**: Apple Music–style frosted mini player between clock and dock, only while something plays
or is paused. Up focuses it (turns white); OK play/pause, Left/Right previous/next. Placed from
`HomeExtras.clockBottom()`, so the clock's size moves it automatically. Album art: from the session's bitmap, or
downloaded from `METADATA_KEY_ALBUM_ART_URI` / `ART_URI` / `DISPLAY_ICON_URI` (≤512px, LRU of 24).

**Control Center**: Menu key or the gear. Frosted panel slides in from the right over a dimmed Home: time/date,
profile chip, what's playing, volume and brightness sliders, sound output, network, then round controls (Sleep,
Sleep timer 15/30/60/90/off, StandBy, Night Shift, Screen off, Do Not Disturb, …). Night Shift is a #33FF8A1E
overlay from the accessibility service (covers every app and HDMI). Screen off = backlight 0 + black overlay,
music keeps playing, any key except volume/media wakes it. Don't use fading edges on frosted panels (they draw a
dark band).

**Profiles**: chip in Control Center switches; each has dock_apps, wallpapers, avatar (`avatars/<name>.png`,
original cartoon avatars, never Apple Memoji art). Switching recreates Home.

**Weather**: pill top-left (Left from the gear, OK) opens an Apple Weather–style card: now, next 6 hours with
rain chance, 5 days with range bars. Open-Meteo, refreshed ≤ every 30 min while Home shows.

**StandBy** (Control Center → StandBy, and the screensaver after 10 min idle): faces switched with Left/Right
and CH+/CH−: digits, analog, calendar, Solar, Float, Now Playing. 22:00–06:00 dim red. While music plays it keeps
the TV awake and opens on Now Playing (switches there if music starts later). Media keys pass through to the
player; they must not close StandBy. Remove the old face view before adding the next (faces once stacked).

**Now Playing face**: blurred art behind; song + time on one line; big art beside the lyrics in a ScrollView
column (a plain layout stops drawing lines below the viewport) with fading edges; progress bar; this week's
calendar; accent = the cover's most vivid colour. Bare-glyph previous / play / next under the art with a white
focus platter (52/64/52dp) and `clipChildren=false` up the chain so the platter isn't clipped. OK play/pause,
Down to the buttons, Up to leave.

**Lyrics**: LRCLIB `/api/search` by title + lead artist (strip "(feat. …)", "- Remastered"), free-text fallback;
prefer synced within 5s of the song length, else any synced, else plain spread over 8–95% of the song. Retry
3× on 5xx and **don't cache failures as "no lyrics"**. Timing: `position − output latency` (≈220ms on Bluetooth)
plus `lyrics_offset_ms`, scaled by lyric-file length / track length when within 10%. The current line is lit by a
soft-edged clipped sweep drawn every frame (`postOnAnimation`), never by re-laying out text. Breathing dots
"•  •  •" only for gaps ≥ 3s (incl. an intro ≥ 3s), as their own row, never as an overlay.

## 6. Extending

- New look options go behind a new theme key in `Config.java` that defaults to off, read in `Config.load`, reset
  at its start.
- Match the code around you: its comment density and voice, Java without libraries, `dp` math via
  `getDisplayMetrics().density`.
- After each change: build/install (section 3, including the SleepService line), screenshot, compare.

## 7. Testing on the TV

- Screenshot: `adb -s <ip>:5555 exec-out screencap -p > shot.png` and look at it.
- Keys: `adb shell input keyevent <KEY>` (HOME, DPAD_UP/DOWN/LEFT/RIGHT, DPAD_CENTER, BACK, MENU, CHANNEL_UP,
  MEDIA_PLAY_PAUSE). **Screenshot before every OK.** Control Center's grid changes with what's shown (a profile
  chip adds a row), so counted presses land on different buttons. A blind OK once hit **Sleep**: the TV went to
  standby and network ADB was gone until the remote woke it.
- The owner may be watching or using the TV. Keep presses few, never pause their music to test, and say what
  you're about to press.
- To capture all wallpapers, push a config without `profiles` (each profile shows only its own set), press CH+
  between shots, then push the real config back.
- StandBy lyrics, album art and the Now Playing card can only be checked while music plays: ask the owner to
  play something.
- Logs: `adb logcat -d | grep -E "Lyrics|tvhome|FATAL"`.

## 8. Finish

1. Show the owner screenshots. Update README screenshots if the look changed (1920×1080 JPG, quality ~85).
2. Commit in conventional style (`feat:`, `fix:`, `docs:`); **push only after the owner says so**.
3. Remind them: turn Network debugging off on the TV when done; `scripts/restore.sh` undoes everything.
