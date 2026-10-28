# AGENTS.md: TV Upgrade

You are in a repo that turns an Android TV / Google TV into a debloated TV with an Apple TV / iOS lock-screen style
home screen: a tall liquid-glass clock with depth cut-outs, an 8-icon dock, Now Playing, Control Center, profiles,
weather, and StandBy with synced lyrics. The README shows the result; this file and the skill tell you how to
rebuild it on someone else's TV.

**Read [`.claude/skills/tv-upgrade/SKILL.md`](.claude/skills/tv-upgrade/SKILL.md) before touching anything.** It
is the step-by-step playbook: setup, debloat, launcher, theme, grants, each feature's design values, how to test
on the TV, and the traps we already hit. Claude Code loads it as a skill on its own; other agents should read it
as a document.

## Rules that are never optional

1. **Disable, never uninstall.** Packages are turned off with `pm disable-user`, after a backup
   (`scripts/debloat.sh` makes one). Everything must stay undoable with `scripts/restore.sh`.
2. **Ask the owner before anything leaves the machine or changes the TV's state** beyond what they asked for:
   git pushes, debloating, changing DNS, toggling settings, pressing OK on a TV control.
3. **One key press at a time when you drive the TV, and look before you press OK.** A blind OK in Control Center
   can put the TV to sleep, which drops network ADB until someone wakes it with the remote.
4. **Never put credentials, the TV's ADB key (`tools/adbkey/`) or backups (`backup/`) in git.**
5. **When you are done, tell the owner to turn Network debugging off.** Port 5555 is plain text.
6. Keep comments, naming and style as the surrounding code has them (plain Java, no libraries, minSdk 26).
