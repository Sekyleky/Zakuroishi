# Android app interface

The library supports search, display names, a separate per-game settings dialog,
system light/dark colors, font scaling and controller focus. See
[frontend behavior](../../docs/frontend.md). New imports store a bounded
`display-name.txt` next to the immutable import. Existing imports still work and
can be renamed. Import progress reports bytes and completed files; activation
reads and commits share one lock without blocking on the bulk copy.

Package: `io.github.zakuro.android`. Launcher: `.Launcher`. Native activity: `.Game`,
non-exported in `:game`, loading `libzakuro_android.so`. Android 11/API 30 minimum.
Package only `arm64-v8a` native libraries; the launcher also rejects non-ARM64 devices.
The manifest requires Vulkan 1.3 (0x00403000); no GLES or optional Vulkan hardware level is required. No signing identity or key is stored here.

Select **one game's built folder**, not an entire multi-game library. SAF traversal
copies ROMs and title.so plus files inside textures/sysdata with a 64 KiB buffer, rejects unsafe/duplicate names and cycles,
and limits nesting and document count. The folder must contain exactly one original
`.3ds`, `.cci` or `.cxi` ROM and one Android ARM64 ELF shared object named `title.so`;
x86 title.so variants are ignored. Encrypted ROMs are rejected. An optional single
`textures` directory can be nested anywhere in the selected folder.
An ARM64 Linux build is not interchangeable with an Android build; header checks
alone cannot establish ABI compatibility. Loading the native library remains the
runtime's final check.

The Play intent sends:

| Extra | Type | Value |
| --- | --- | --- |
| `game` | String | Absolute private import `game` directory containing `game.3ds` |
| `code` | String | Absolute private import `code/title.so`, read-only before launch |
| `data` | String | Absolute private import `data` directory |
| `texture_packs` | boolean | Per-title preference, default true |
| `resolution` | int | Per-title preference 1, 2 or 3, default 1 |

Runtime integration: use the explicit `data` extra as core `Config.data_dir`.
Do not derive it from the game folder. Upstream `System::new` uses `data/user`;
`mods::textures` uses `data/mods/<16-digit-uppercase-title-id>/textures`.
Each immutable import's `data/user` is an app-created symlink to stable private
`files/savedata/<title-id>/user`. Reimport never overwrites this directory. Selected
source `data` content is copied as payload but is not installed as user savedata.
The native runtime must read `texture_packs` via `getBooleanExtra` and `resolution`
via `getIntExtra` and apply them to core Config; Java supplies both extras.

Import completion atomically switches a small `AtomicFile` pointer. Failed imports
clean their staging directory without following `data/user`; the prior pointer
remains active. Old successful versions are intentionally retained so a running game
can finish against its original files. Process death during import may leave an
unreferenced staging directory. There is no storage-management UI in this version.
Imports survive activity recreation but not process death; keep the app open while
importing and retry if Android terminates it. Executable imports must be trusted; the launcher displays this warning. ELF checks verify ARM64/shared-object headers, not runtime ABI version or correspondence to the ROM.

Validation performed: Java 11 compilation against the installed Android SDK,
`aapt2 link` on the manifest, and `tests/GameImportCheck.java` host checks for path
traversal, ARM64 ELF identity, ROM title ID, encrypted/truncated ROM rejection and
cleanup boundaries. Device SAF, renderer and lifecycle smoke tests remain required.


Native errors: call `Game.showRuntimeError(String)` with JNI signature
`(Ljava/lang/String;)V`. The method posts to the UI thread and shows a noncancelable
error dialog. Its Return to library button finishes the activity. Do not also call
finish or exit the process immediately after reporting an error. The current native
`showNativeError(String)` call is supported as an alias. Pending error text survives
activity recreation; duplicate reports do not stack dialogs.

An optional unique source `sysdata` directory is moved to `<data-extra>/sysdata`,
where core shared-font and Mii-resource lookup can find it. Multiple sysdata folders
are rejected. This does not touch the separate stable user saves directory.
