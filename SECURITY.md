# Security Policy

## Supported versions

Only the [latest release](https://github.com/Asta39/tvupgrade/releases/latest) gets fixes. Releases from v1.0.1 on
are signed with the project's release key:

```
SHA-256 41a14a294724eae10ecfc4f24d6b8ba2559ff3f1d43fa7d6054f5e260b7ef32f
```

An APK signed with any other key did not come from this project.

## Reporting a vulnerability

Please don't open a public issue. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/Asta39/tvupgrade/security/advisories/new), with
the version, the TV model and the steps to reproduce it. You'll get a reply within 7 days, and a fix or a plan
within 30 days for confirmed issues. You'll be credited in the release notes unless you'd rather not be.

## What this project does that matters for security

- **ADB.** Setup and the remote use the TV's own debugging port (`127.0.0.1:5555`). The classic ADB port is not
  encrypted, so anyone on the same network can reach it while it's on. Turn **Network debugging** off when you don't
  need it. The app keeps its ADB key in its private storage.
- **The Guest mode phone page** (`http://<tv>:8686`) is plain HTTP on the local network. The host page needs a PIN.
  The PIN is stored only as a SHA-256 hash, and sign-in locks for five minutes after five wrong tries. The guest
  page is open to anyone on the Wi-Fi: it shows the Wi-Fi password, the house rules and, only during a stay, the
  door code.
- **Debloating** only disables packages for the current user. Nothing is uninstalled, and `scripts/restore.sh`
  turns everything back on.

## Out of scope

Problems in the TV maker's firmware, Google's apps, or the third-party apps you install, unless this project makes
them worse.
