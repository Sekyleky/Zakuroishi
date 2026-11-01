# Native Zakuroishi support in iiSU's Nintendo 3DS library

Prepared for upstream review; not submitted. This is a request for iiSU's shipped
catalog, not instructions for users to edit their own configuration.

## Requested behavior

With Zakuroishi (formerly Zakuro Android) installed, a user chooses **Add game library folder** in
stock iiSU, selects a library containing Nintendo 3DS games, and can use Zakuroishi
as an installed 3DS player. They should not need a custom command, edited JSON,
imported emulator catalog, patched iiSU APK or compatibility app pretending to
be another emulator.

## Why an upstream change is needed

Inspected the installed `com.iisulauncher` version **0.1.6.1**, versionCode **9**
on an AYN Thor on 2026-11-01. Its active Nintendo 3DS catalog contains Azahar,
AzaharPlus, RetroArch and other named players, but not `io.github.zakuro.android`.
The Nintendo 3DS extension list does not include `.zkr`.

Read-only inspection of that APK's emulator resolver confirms that automatic
detection iterates the selected console's configured emulator definitions and
their packages. It does not query Android ACTION_VIEW handlers to add unknown
players. Folder files cannot register a new player in that resolver. This is
distinct from Android Files, where a reference can already be opened with Zakuro.
No iiSU app, catalog or settings were modified during this investigation.

## Proposed catalog addition

The machine-readable contribution is
[`integrations/iisu/zakuro-support.json`](../../integrations/iisu/zakuro-support.json).

1. Append its `emulator` entry to Nintendo 3DS (`n3ds`) in the shipped emulator
   catalog, preserving existing players and commands.
2. Add its `supportedEmulator` entry to the supported-emulator list.
3. Add `.zkr` to Nintendo 3DS's accepted extensions. Original decrypted
   `.3ds`, `.cci` and `.cxi` files already use existing recognized extensions.
4. Publish through iiSU's ordinary catalog/update mechanism so users do not need
   to import or hand-edit anything.

## Launch contract

- Package: `io.github.zakuro.android`
- Exported activity: `io.github.zakuro.android.FrontendLaunch`
- Action: `android.intent.action.VIEW`
- URI: `%ROM_URI%`, with read permission for content URIs.
- Reference format: 33-byte ASCII `ZAKURO-LAUNCH-1\nTITLE_ID\n`, where TITLE_ID is
  the imported game's 16 hexadecimal digits.
- Original decrypted NCSD/NCCH ROM URIs are also supported. Zakuro reads their
  title ID and opens the already installed title.

Games must first be imported into Zakuro with compatible Android native game
code. This entry launches installed games; it does not execute arbitrary native
libraries provided by a frontend. The existing game's settings and saves apply.

Reference files contain no ROM data and must not be ROM-hashed for achievement
identification. They should use filename metadata/normal scraping instead.

## Existing verification

Zakuro Android 0.1.8 is available at
https://github.com/Sekyleky/zakuro-android/releases/tag/android-v0.1.8 .
On the Thor, opening a `.zkr` through Android Files with a real document URI grant
launches Ultra Moon. Direct `zakuro://game/TITLE_ID` links work too. Repeated
launches reuse the active game. Reference parsing and malformed-input checks
pass on the host and Android runtime.

After iiSU ships this entry, verify stock **Add game library folder**, installed
player detection, both original-ROM and `.zkr` launches, URI grants, returning to
iiSU and lower-display launching. These stock-iiSU flows are not yet verified
and are not claimed to work in the current catalog.
