# Zakuro Android 0.1.4 — experimental prerelease

An unofficial Android fork of [Zakuro](https://github.com/fearkov/zakuro), based on
v0.2.30, aimed at powerful ARM64 handhelds such as the AYN Thor. Thanks to fearkov
for Zakuro and 3dsrecomp, whovian64 and Pocket Native contributors for their Android
work, and the libadrenotools/linkernsbypass contributors. Original licenses remain
in the repository and APK.

## Install

1. Download **Zakuro-Android-0.1.4.apk** below and install it on an **ARM64 Android
   11+** device with a **Vulkan 1.3** driver.
2. Existing testers can update directly without uninstalling. Keep the same app
   installation to preserve its data. No new game-code build is needed for 0.1.4.
3. New users: follow the [setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.4/docs/android.md).
   Prepare your decrypted ROM and matching Android ARM64 **ABI-4** `title.so`
   using the [computer-side builder](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.4/docs/native-library.md).
   Then choose **Add game** and select their folder. Texture packs are optional.

The app does not compile games on the handheld. Pocket Native ABI-5/fastmem code,
desktop DLLs and Linux libraries are not compatible. No ROMs, native game code,
firmware, keys or texture packs are distributed. Only import native code and
custom drivers from sources you trust.

## Included

- Android game library with validation before copying and code-only repair.
- Optional Citra-format HD texture replacement, without modifying the ROM.
- Per-game display, rendering, controller, motion, audio and system settings.
- Pause menu, save states, screen swapping and gyro recalibration.
- Dual-display routing and optional custom Adreno driver loading.
- A fix for the 90-degree sideways display reported in 0.1.3.

## What to expect

This is an early public test build, not a stable compatibility release. A tester
reported a game launching on Thor with 0.1.3, with rotated screens; the 0.1.4 fix
passes targeted regression tests but still needs physical Thor confirmation.
Motion, second-display behavior and custom-driver combinations need more device
testing. The second display currently uses GPU readback, and compositor rotation
can add overhead. **Faster-than-Azahar performance has not been demonstrated.**

The APK build, signature and alignment checks passed. Detailed automated results
and their limits are in the [verification record](https://github.com/Sekyleky/Zakuroishi/blob/android-v0.1.4/docs/testing.md).
The APK uses the same local test signing identity as earlier builds. This is not
a Play Store release. SHA256SUMS.txt accompanies the APK.

Report device, Android/app version, driver, game build, settings and reproduction
steps through [Issues](https://github.com/Sekyleky/Zakuroishi/issues/new/choose).
Please do not upload games, compiled game libraries, keys or personal save data.
