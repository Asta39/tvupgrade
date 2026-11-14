# Contributing

Thanks for helping. This project changes real people's TVs, so the rules below are mostly about keeping those
TVs safe.

## Ground rules

1. **Disable, never uninstall.** Packages are turned off with `pm disable-user`, after a backup, so
   `scripts/restore.sh` can undo everything.
2. **Debloat lists are per model.** A wrong list can stop a TV from starting (see the Vitron warning in
   `debloat/vitron-cvte-mt5867.txt`). Name a new list after the model, start it with a `# model:` line using
   `ro.product.model`, `ro.product.device` or `ro.product.name`, and keep firmware plumbing (tuner, inputs, setup,
   updates) on.
3. **Test on a real TV and restart it.** Say in your pull request which TV, Android version and firmware you used,
   and that it booted back to a working home screen after a full restart.
4. **Never commit secrets:** keystores, passwords, ADB keys (`tools/adbkey/`), backups (`backup/`), Wi-Fi passwords
   or door codes.

## Getting set up

You need Google's `adb`, Python 3, JDK 17 (not newer) and the Android SDK. Then:

```bash
scripts/install-launcher.sh <tv ip>   # build and install the launcher
scripts/push-theme.sh <tv ip>         # wallpapers, depth cut-outs, layout
scripts/grant.sh <tv ip>              # one-time permissions
```

After every reinstall, run
`adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/.SleepService` again.

Coding agents: read [`AGENTS.md`](AGENTS.md) and the skill in `.claude/skills/tv-upgrade/` first.

## Code style

- Plain Java, no new libraries unless they earn it, `minSdk 26`.
- Match the code around you: its comment density, naming and voice.
- New look options go behind a theme key in `Config.java` that defaults to off.
- Remote first: everything must be reachable with a D-pad and show a clear focus state.

## Commits and pull requests

- Conventional commits: `feat:`, `fix:`, `docs:`, `build:`, `chore:`.
- One topic per pull request. Fill in the template, with screenshots from the TV
  (`adb exec-out screencap -p > shot.png`) for anything visual.

## Reporting bugs and asking for features

Use the [issue forms](https://github.com/Asta39/tvupgrade/issues/new/choose). For security problems, see
[`SECURITY.md`](SECURITY.md) instead of opening a public issue.

By contributing, you agree your work is released under this repository's [MIT License](LICENSE) and that you
follow the [Code of Conduct](CODE_OF_CONDUCT.md).
