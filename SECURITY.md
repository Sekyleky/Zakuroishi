# Security

Zakuroishi runs native code that you build or download yourself. This page says what that
means and what the app does and does not check.

## What you are trusting

These run or load with the app's own access (its private files, including every game's
saves) and nothing more:

- **A game's `title.so`.** It is machine code that executes inside the app's `:game` process.
- **A custom Vulkan driver ZIP.** It is loaded into the same process as a graphics driver.
- **Mods.** Code mods (`code.ips`, `code.bps`, `code.bin`) change the code the game runs,
  and file mods change its files.
- **Frame generation.** The shaders it uses are translated on the device from your own
  `Lossless.dll`. The output is stored and run as GPU code.

Use only files you built yourself or got from someone you trust.

## What the app checks

Structure, not safety. Import checks that a ROM header, ELF exports and ABI value, PNG
chunks and driver metadata are well formed and fit the free space, before copying anything.
A file that passes is not shown to be harmless: the app never runs a library to inspect it,
and a well-formed library can still do anything the app can do.

## What it cannot check

- Whether a `title.so`, driver or mod is malicious.
- Whether a `title.so` was built from the ROM you imported, or with the generator you
  think you used.
- Whether a `Lossless.dll` is the genuine one.

## Network

The manifest (`android/app/src/main/AndroidManifest.xml`) declares no permissions, so
the app has no `INTERNET` permission and its own code, including a `title.so`, cannot open
network connections. That limits sending your files away from the device. It does not stop
a bad library from damaging or deleting what the app can reach, such as saves and imported
games, or from crashing the device's graphics driver. Android's sandbox still applies.

## Saves and the file picker

Each game's in-game saves are offered as **Zakuroishi saves** in Android's file picker, for
save editors. The provider needs a system permission, so only the picker can open it, and
you choose each file. An app you give a save file to can read and change that save. Writes
are refused while the game runs; files cannot be created, renamed or deleted from the
picker. Releases up to 0.3.1 copy the saves to `user.previous` before an editor writes.
After that, the app keeps the 3 newest backups as `user.backup-<ms>`, shown read-only under
`:backups` in the picker. Export a ZIP first if the save matters.

## Check a build

- **APK.** Compare the download with `SHA256SUMS.txt` from the release. Check the signer
  with `apksigner verify --print-certs`. The published fingerprint is in the
  [README](README.md#verify-the-signing-certificate).
- **Native code and drivers.** The app shows a SHA-256 when you add a game, replace native
  code or import a driver (from the next release). Compare it with the hash of the file you
  built or downloaded, for example `Get-FileHash` or `sha256sum`.
- **Build it yourself.** [Development](docs/development.md) has the steps. A self-built APK
  has a different signing key and will not update over a published one.

## Report a problem

Open an issue at <https://github.com/Sekyleky/Zakuroishi/issues/new/choose>. GitHub's private
vulnerability reporting is not switched on for this repository. For a security problem,
say so in the title and leave out exploit details, keys and personal data; a way to share
the details privately can be arranged in the issue.
