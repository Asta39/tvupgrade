# Which TVs it works on, and what you need

Home is an Android app. It runs on any TV or streaming box that runs **Android TV or Google TV, Android 8.0
(API 26) or newer**, with an ARM or x86 chip. This page covers which devices work, how well, and everything else
you need: the remote, the network, the phone, and the permissions the app asks for.

## At a glance

| Device | Installs | Can be Home | Full feature set | Notes |
|---|---|---|---|---|
| **Google TV** (TCL, Hisense, Sony, Philips, Chromecast with Google TV, Onn) | ✅ | ✅ | ✅ | The main target. Tested on a **TCL G10**, Google TV 12. |
| **Android TV** (older Sony, Philips, Xiaomi Mi Box / TV Stick, Nvidia Shield) | ✅ | ✅ | ✅ | Same as Google TV. |
| **Budget Android TV boards** (CVTE / MediaTek, e.g. Vitron) | ✅ | ✅ | ✅ | Tested on a **Vitron** (CVTE MT5867, Android 11). Low memory: run the debloat. |
| **Projectors on Android TV** (XGIMI, Formovie) | ✅ | ✅ with the Home redirect | ✅ | Firmware may turn its own launcher back on; see *The Home button* below. |
| **Amazon Fire TV / Firestick** | ✅ | ⚠️ Partly | ⚠️ Most features | Not tested yet. See *Fire TV* below. |
| **Samsung (Tizen), LG (webOS), Hisense VIDAA, Whale OS, Roku** | ❌ | ❌ | ❌ | Not Android: they can't install APKs. Plug in a Google TV stick instead. |
| **Android phones and tablets** | ✅ | — | — | It installs, but it's built for a TV remote and a 16:9 screen. |

**Not an Android TV?** A Chromecast with Google TV, Onn 4K box or Xiaomi TV Box costs little, plugs into HDMI, and
gives you the whole thing. Set the TV to start on that HDMI input.

## Fire TV and the Firestick

- **Installing:** works. Fire OS 7 is Android 9 and Fire OS 8 is Android 11, both above the minimum. Install it with
  Downloader (*Settings → My Fire TV → Developer options → Install unknown apps*).
- **As Home:** Amazon blocks other apps from replacing its home screen. The Home button always goes to Amazon's
  launcher first. The *Home button helper* (an accessibility service, see below) can send it straight back to this
  launcher, the same way it does on TVs whose own launcher can't be switched off.
- **What's untested:** this hasn't been tried on a Firestick yet, so expect rough edges. Settings opens Amazon's
  own settings menu, which the app already knows how to find.
- **Never** disable Amazon's launcher (`com.amazon.tv.launcher`) or its setup apps: the stick can stop starting.

## What you need

### Always
- An Android TV / Google TV device on **Android 8.0 or newer**.
- About **60 MB** free (the APK is about 40 MB, with nine 4K wallpapers inside).
- **Install unknown apps** allowed for whatever installs it (Downloader, Orion Store, or a file manager).

### For the full setup (strongly recommended)
Some features need permissions Android only gives over ADB, Android's debugging connection. The welcome screen does
this for you if you switch on debugging:

- *Settings → Device Preferences → About → Build*: press OK seven times to unlock Developer options.
- *Developer options → USB debugging* **on**, and *Network debugging* if your TV shows it.
- Accept the "Allow debugging?" prompt on the TV.

**When setup has finished, switch Network debugging off again.** Port 5555 isn't encrypted.

### For the phone remote, Guest mode and party music
- The phone and the TV on the **same Wi-Fi network**.
- Any phone with a web browser (iPhone or Android). Nothing to install: guests scan the QR code.
- The TV serves the phone page on port **8686**. Networks that isolate devices from each other (some hotel and
  guest Wi-Fi) block this.

### Optional apps the features use
| Feature | Needs |
|---|---|
| Party music | **BitChord TV** installed on the TV |
| YouTube | **TizenTube** (the app opens it before the stock YouTube) |
| Weather | Internet; the city is set in the theme or picked at setup |
| Synced lyrics in StandBy | Internet (lyrics from LRCLIB) |
| Updates from Control Center | Internet, and *Install unknown apps* allowed for Home (asked once) |

## Which features need what

| Feature | Plain install | With the ADB setup |
|---|---|---|
| Home screen, dock, profiles, wallpapers, glass clock | ✅ | ✅ |
| Weather, StandBy screensaver, Control Center | ✅ | ✅ |
| Opens when you press **Home** | Pick it once when Android asks | ✅ set automatically |
| Home button always comes back here (even after a Google TV update) | — | ✅ Home button helper |
| Now Playing and media controls | — | ✅ notification access |
| Sleep, Screen off, Night Shift, Sleep timer | — | ✅ Sleep helper |
| Brightness and Do Not Disturb in Control Center | — | ✅ |
| Phone remote arrows, typing with the phone keyboard | — | ✅ |
| Removing the TV's ads and trackers | — | ✅ only on TVs with a tested list |

## The Home button

Android lets one app be Home. On Google TV the stock launcher can update itself from the Play Store and take Home
back. Two things keep this launcher in charge:

1. The setup sets it as Home with `cmd package set-home-activity`.
2. The **Home button helper** (Settings → Accessibility → *Home button*) catches the Home press and opens this
   launcher directly, and sends the stock home screen back here whenever it appears. The theme setting
   `stock_home_redirect` names the stock launcher to watch for, and `restore_key_services` turns the helpers back on
   if a cleaner app or a force stop switches them off.

If the TV still lands on the old home screen after an update, run `scripts/install-launcher.sh <tv ip>` again, or
re-run the welcome screen's debugging step.

## Removing bloat

The clean-up only runs on models with a tested list in `debloat/`:

| List | TV |
|---|---|
| `tcl-g10-google-tv.txt` | TCL G10, Google TV 12 (STT2.230203.001) |
| `vitron-cvte-mt5867.txt` | Vitron smart TV, CVTE / MediaTek MT5867, Android 11 |
| `privacy-list.txt` | Known tracking and ad apps, offered on other models |

Apps are **disabled, never uninstalled**, and `scripts/restore.sh` turns them all back on. Never disable the TV's own
home screen or setup app: some Google TVs restart forever without them. For other models, start from
[cleanyourtv's examples](https://github.com/Gathin23/cleanyourtv/tree/main/examples).

## Permissions, and why

| Permission | Used for |
|---|---|
| Internet, network and Wi-Fi state | Weather, lyrics, posters, updates, the network status, the phone remote |
| Location (fine) | Reading the Wi-Fi network's name for the network status; Android requires it for that |
| Query all packages | Listing the TV's apps for the dock and the app grid |
| Read TV listings / EPG | Live TV channels on TVs that have a tuner |
| Notification policy | Do Not Disturb in Control Center |
| Write settings | Screen brightness |
| Write secure settings (ADB only) | Turning its own helpers back on; never granted without ADB |
| Install packages | Installing updates of this launcher from Control Center |
| Accessibility: *Home button*, *Sleep* | The Home key, Sleep, Screen off and Night Shift. They read keys only, not what's on screen |
| Notification listener | Now Playing |

Nothing is sent anywhere except the requests above. There's no tracking or analytics.

## Updating

- **From the TV:** Control Center shows a card under *Sound output* when a new release is out. Download, then
  Install. The first time, Android asks you to let Home install apps.
- **From Orion Store:** it updates from this repo's releases.
- Updates install over the top and keep your settings. The one exception: an APK signed with a different key can't
  update in place (for example, a build you made yourself). Uninstall first, then install.

## Troubleshooting

| Problem | Fix |
|---|---|
| Home goes to the old launcher | See *The Home button*. Check the Home button helper is on in Accessibility. |
| The phone can't open the remote page | Same Wi-Fi? Some guest networks isolate devices. Try the address under the QR code. |
| Phone arrows say "Buttons off" | The ADB setup wasn't run. Re-run the welcome screen's debugging step. |
| Now Playing is empty | Notification access isn't granted: run `scripts/grant.sh <tv ip>`. |
| Party music doesn't play | Install BitChord TV and open it once on the TV. |
| "Signed with a different key" on update | Uninstall Home once and install the release; updates work from then on. |
| Everything is slow | Low-memory TVs (1–2 GB) need the debloat; a cleaner app killing Home also breaks the Home button. |
