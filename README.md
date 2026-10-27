# TV Upgrade

**A TCL Google TV, debloated and given an Apple TV–style home screen.** Big lock-screen clock, scenery that stands in
front of the time, a row of iOS-style app icons along the bottom, and nothing else.

![Home screen, golden peak](screenshots/home-3.jpg)

Built on a TCL G10 (Google TV 12) with [Claude Code](https://claude.com/claude-code), starting from
[cleanyourtv](https://github.com/Gathin23/cleanyourtv): no root, no firmware flashing, and every change can be undone.

---

## The look

| | |
|---|---|
| ![Forest rays](screenshots/home-1.jpg) | ![Misty pines](screenshots/home-2.jpg) |
| ![Fog forest](screenshots/home-4.jpg) | ![Green lake](screenshots/home-5.jpg) |
| ![Tea hills](screenshots/home-6.jpg) | ![Flower sunset](screenshots/home-7.jpg) |
| ![Dark fern](screenshots/home-8.jpg) | ![Fern canopy](screenshots/home-9.jpg) |

All nine wallpapers, one press of **CH +** apart:

![All nine wallpapers](screenshots/all-wallpapers.jpg)

**All apps** (press Down):

![All apps](screenshots/all-apps.jpg)

**Now Playing:** while music plays, a small frosted mini player (Apple Music style) sits between the clock and the
dock. Press Up to focus it: it turns white, OK plays or pauses, and Left/Right skip tracks.

![Now Playing card](screenshots/now-playing.jpg)

![Now Playing, focused](screenshots/now-playing-focus.jpg)

**Control Center:** press the gear button or the remote's Menu key. A frosted panel slides in from the right over
a dimmed home screen.

![Control Center](screenshots/control-center.jpg)

Scroll down for the controls. **Night Shift** warms everything on screen, every app and HDMI included:

| | |
|---|---|
| ![Control Center controls](screenshots/control-center-controls.jpg) | ![Night Shift on](screenshots/night-shift.jpg) |

**Profiles:** pick the person in Control Center's top-right chip. Each has their own dock and wallpapers
(here Bond, with Prime Video and Reezn in the dock):

![Bond's profile](screenshots/profile-bond.jpg)

**Weather, avatar:** a weather pill top left (Left from the gear, then OK) opens an Apple Weather–style card:
current conditions, the next six hours with rain chance, and five days with temperature range bars. The current
profile's avatar sits next to the gear.

| | |
|---|---|
| ![Home with weather pill and avatar](screenshots/home-avatar-weather.jpg) | ![Weather card](screenshots/weather.jpg) |

**Avatars:** original 3D cartoon avatars generated for each profile (not Apple's Memoji art).

![Ian and Bond](screenshots/avatars.png)

**StandBy:** Control Center → StandBy. Three faces on black, switched with Left/Right: huge digits, an analog
clock, and a clock beside this month's calendar. From 22:00 to 06:00 they turn dim red, like iPhone StandBy's
night mode.

![StandBy faces](screenshots/standby.jpg)

Three more faces after iOS StandBy: **Solar** (the sky's colour for the hour, with the current weather symbol
from the home screen on the sun's arc), **Float** (huge numerals, one colour each), and **Now Playing**, laid out
like Apple Music: blurred artwork behind, the song and the time on one line, big artwork beside five lines of
time-synced lyrics, a progress bar and this week's calendar. The clock, the current lyric, the progress and today
take the cover's most vivid colour.

![Solar and Float](screenshots/standby-solar-float.jpg)

![Now Playing with lyrics](screenshots/standby-lyrics.jpg)

Lyrics come from [LRCLIB](https://lrclib.net), a free community lyrics database, looked up by the song's title,
artist and length: music apps don't share their own lyrics with other apps. It searches by title and lead artist ("(feat. …)"
and "- Remastered" are ignored), prefers synced lyrics within five seconds of the song's length, retries when
LRCLIB is busy, and spreads plain (untimed) lyrics over the song when that's all there is. Songs LRCLIB doesn't
have show "No lyrics for this song". The remote's play/pause and next keys work while StandBy is up.

**Before:** stock Google TV, with sponsored rows, promos and a profile wall.

![Stock Google TV home](screenshots/before.jpg)

## What's on screen

- **Lock-screen clock.** A large, thin, centred clock that fades towards the bottom, with the date above it.
- **Depth effect.** On six of the nine wallpapers the mountain, trees or hills are cut out and drawn *in front of*
  the clock, like the iPhone lock screen. The top of the digits always stays readable.
- **Eight icons, nothing else.** Netflix, Bitchord, TizenTube, Crunchyroll, Shuffler, AniFam, Spotify and Apple TV,
  as full-bleed rounded squares along the bottom edge. The selected icon lifts slightly.
- **All your other apps** wait below the dock. Press **Down** and they slide up while the clock fades out. Press
  **Up** and you're back.
- **One quiet button** in the top right: Control Center (Inputs lives inside it).
- **Now Playing:** an Apple Music–style mini player that appears only while something is playing or paused, and
  steps aside when you browse the apps below.
- **Control Center:** the time and your profile, what's playing with back/play-pause/next, volume and brightness
  sliders, where sound is going (e.g. a Bluetooth soundbar) and the network, then nine round controls:
  Sleep, Sleep timer (15/30/60/90 min/off), Screensaver, Night Shift, Screen off (music keeps playing; any button
  wakes it), Do Not Disturb, next Wallpaper, Inputs and Settings.
- **Profiles:** each person has their own dock and wallpaper set (`profiles` in the theme's `config.json`).
- **Nine 4K wallpapers** that rotate every hour with a soft cross-fade.

## Remote

| Button | On the home screen |
|---|---|
| **CH +** / **CH −** | Next / previous wallpaper (in Live TV they still change channels) |
| **Down** | Show all apps |
| **Up** | Back to the dock, then up to Inputs / Settings |
| **OK** on an icon | Open the app |
| **Menu** or the gear | Control Center (Back closes it) |
| **Up** from the dock, then **OK** / **Left** / **Right** | Play/pause / previous / next on the Now Playing card |

## What was removed

Twenty-two packages were **disabled, not uninstalled**: the 21 in [`debloat/tcl-g10-google-tv.txt`](debloat/tcl-g10-google-tv.txt), plus the stock launcher, which the installer turns off once the new Home works:

- Samba TV viewing tracking (ACR) and TCL log upload
- TCL Channel, TCL Live, and the promo rows TCL pushes to the home screen
- Store demo mode, the e-manual, the repair guide, the TGuard cleaner, MagiConnect and Alexa
- Google Feedback, TalkBack, YouTube Music and Play Games
- The stock Google TV launcher and the setup wizard (which takes Home back otherwise)

Kept on purpose: voice/mic, Chromecast, AirPlay/Miracast, the ambient screensaver, the game bar and firmware updates.

Private DNS is set to AdGuard (`dns.adguard-dns.com`) to block ad and tracker domains. It does not block YouTube ads.

## How it's built

The launcher in [`launcher/`](launcher/) is cleanyourtv's reference launcher (plain Java, about 2 MB, made for TVs with
1 GB of RAM) with these additions. Each one is a theme key that stays off unless a theme turns it on:

| Theme key | What it does |
|---|---|
| `icon_fill` | Square tiles whose app icon fills the card, with iOS-proportioned corners (22.5%) |
| `columns` up to 14 | Stock caps at 8 |
| `hero_clock` | The centred lock-screen clock and date |
| depth cut-outs | `depth/<wallpaper name>.png` is drawn over the clock and cross-fades with its wallpaper |
| `dock_glass` | Frosted dock made from a pre-blurred copy of the wallpaper (no live blur). Off in this theme |
| `keep_watching` | A row of Continue watching cards with posters. Off in this theme, and the only part that uses the internet |
| CH+/CH− | Step through the wallpapers on Home |
| `now_playing` | The mini player, from Android's media sessions (needs the notification-listener grant below) |
| `control_center` | The Control Center panel. Sleep, Night Shift and Screen off use a small accessibility service that filters no keys, so held volume still ramps |
| `profiles` | A list of `{name, dock_apps, wallpapers, avatar}`; the chip in Control Center switches between them. Avatars live in the theme's `avatars/` folder |
| `weather_city`, `weather_lat`, `weather_lon` | The weather pill and forecast card, from Open-Meteo (free, no key), refreshed at most every 30 minutes while Home is showing |
| StandBy | `StandByActivity` from Control Center; also installed as a screensaver named StandBy (Settings → Screen saver, or `settings put secure screensaver_components com.gathin.tvhome/.StandByDream`) |

The theme itself is [`themes/ian-dusk/`](themes/ian-dusk/): `config.json`, nine 3840×2160 wallpapers, and six depth
cut-outs. Forest rays and the two ferns have no cut-out on purpose: their busy foliage fills the frame, and
cutting it out made the bottom of the digits look broken rather than layered.

### How the wallpapers were made

1. Portrait photos were rotated to landscape.
2. Each was AI-upscaled to 4K (Magnific Precision, 4–8×) and cropped to exactly 3840×2160.
3. The sky in each was masked with SAM, and the mask was inverted to give the foreground.
4. The cut-out fades out above a set height, so the clock is only partly covered.

The script is [`scripts/make_cutouts.py`](scripts/make_cutouts.py).

## Set it up on your own TV

You need: an Android TV / Google TV on the same network, **Developer options → USB debugging** (and Network debugging
if shown) turned on, Google's `adb`, Python 3, and JDK 17 + the Android SDK for the build.

```bash
scripts/debloat.sh <tv ip>          # backs up, then disables the list (accept the ADB prompt on the TV)
scripts/install-launcher.sh <tv ip> # builds the launcher and makes it Home
scripts/push-theme.sh <tv ip>       # wallpapers, depth cut-outs, layout
```

One-time grants for Now Playing and Control Center (or run `scripts/grant.sh <tv ip>`, which also covers brightness and Do Not Disturb):

```bash
adb shell cmd notification allow_listener com.gathin.tvhome/.GuardRevival
adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/.SleepService
```

The debloat list is tested on a **TCL G10** (Android 12, build STT2.230203.001). For any other TV, start from that
model's list in [cleanyourtv's examples](https://github.com/Gathin23/cleanyourtv/tree/main/examples) instead.

### Customise

- **Dock apps and order:** edit `dock_apps` in `themes/ian-dusk/config.json`, then run `push-theme.sh`.
- **Wallpapers:** put 3840×2160 JPGs in `themes/ian-dusk/wallpapers/`. A wallpaper without a depth cut-out just shows
  the clock in front.
- **Bring back Keep watching or the network pill:** set `"keep_watching": true` / `"show_network_pill": true`.

### Undo

```bash
scripts/restore.sh <tv ip>    # re-enables everything, gives Home back to Google TV, resets DNS
```

To turn one app back on: `adb shell pm enable <package>`.

### When you're done

Turn **USB debugging** and **Network debugging** off on the TV and revoke the authorisations. Port 5555 is
unencrypted, and anyone on your network could reach it while it's open.

## Credits

- [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin23: the launcher, tools and safety rules (MIT, see [LICENSE](LICENSE))
- [Outfit](https://fonts.google.com/specimen/Outfit) typeface (SIL Open Font License, [`launcher/FONT-LICENSE-OFL.txt`](launcher/FONT-LICENSE-OFL.txt))
- Wallpaper photos belong to their photographers
- Design reference: the Apple TV / iOS lock screen
