# Zakuro Android 0.1.5

Save files are now reachable without root or a file manager.

## New: Saves button

Every game in the library has a **Saves** button (also under **Settings → Manage
saves…**):

- **Export ZIP…** writes your in-game saves to any location Android's file picker
  offers: Downloads, an SD card, or a cloud provider such as Google Drive. The ZIP
  holds ordinary save files you can open on a computer.
- **Restore ZIP…** brings a Zakuro save backup for the same game back. The archive
  is fully checked before anything is replaced, and your current saves are kept
  as a local recovery copy.

Save in-game and exit to the library before exporting or restoring. Transfers are
blocked while the game still has its saves open, including when it is paused in
the background. Quick save states are separate and not included; after a restore,
start the game normally instead of loading an older quick save.

## Install

Download **Zakuro-Android-0.1.5.apk** below and install it over 0.1.4 — no
uninstall needed, and your games and saves stay in place. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.5/docs/android.md).

Requirements, limits and credits are unchanged from
[0.1.4](https://github.com/Sekyleky/Zakuroishi/releases/tag/android-v0.1.4).
No games, compiled game libraries, keys or firmware are distributed.
`SHA256SUMS.txt` accompanies the APK.

Please report problems through [Issues](https://github.com/Sekyleky/Zakuroishi/issues/new/choose),
without attaching games, game libraries or personal saves.
