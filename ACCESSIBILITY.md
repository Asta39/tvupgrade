# Accessibility

Home for TV is meant to be used from a sofa with a plain TV remote, by anyone in the household or a guest who has
never seen it before.

## What we aim for

- **Remote first.** Every screen works with the D-pad, OK and Back. Nothing needs a mouse, a touchscreen or a
  keyboard, except typing names and the Wi-Fi password in setup.
- **A clear focus state.** The selected item turns white, or lifts and grows, so it's easy to see from across the
  room.
- **Readable at a distance.** Body text is at least 12sp on a 1080p TV, headings are much larger, and text over
  wallpapers sits on dimmed glass cards or a dark gradient for contrast.
- **Plain language.** Setup and Guest mode use short, everyday sentences and never assume technical knowledge.
- **The phone page** uses real buttons and labels, large tap targets, and follows the phone's text size.

## Supported environments

- Android TV and Google TV, Android 8.0 (API 26) and newer, tested on a TCL G10 (Google TV, Android 12).
- The Guest mode phone page in current Chrome and Safari on Android and iOS.

## Known limitations

- **Screen readers.** The TCL debloat list turns off TalkBack. Re-enable it with
  `adb shell pm enable com.google.android.marvin.talkback` if you need it. Many custom views (the glass clock, the
  StandBy faces) are decorative and are hidden from TalkBack, and not every card has a spoken label yet.
- **Motion.** The clock's glass effect and the lyrics sweep animate. There's no reduce-motion setting yet.
- **Colour.** The accent colours come from wallpapers and album art, so contrast varies from picture to picture.
- **The phone page needs the same Wi-Fi as the TV**, which can confuse guests. The TV says so next to the QR code.

## Reporting a barrier

Open an [issue](https://github.com/Asta39/tvupgrade/issues/new/choose) using the bug form and start the title with
"Accessibility:". Say what you were trying to do, which screen it was, and what made it hard. Accessibility
problems are treated as bugs, not feature requests.
