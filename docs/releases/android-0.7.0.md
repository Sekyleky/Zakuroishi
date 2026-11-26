# Zakuroishi 0.7.0

New screen layouts, screen rotation, the handheld microphone, picture effects and your own picture
shaders, 0.5× to 5× rendering resolution, more button bindings, updates and DLC, and three upstream
Zakuro fixes for Pokémon Ultra Moon. It installs over 0.6.1 and keeps games, settings and saves.

Release smoke test: **not run on a device.** The test handheld was not available for this release. Every change passed the PC checks (Rust tests, including GPU tests that draw on a desktop Vulkan GPU, the ARM64 build check and the Java settings checks). The features marked "not run on device" in the changelog have not been tried on a handheld yet.

## Before you update

- It installs over 0.6.1 and keeps games, settings, in-game saves and save states. What a save
  state holds did not change since 0.6.1, so quick states and slots made with 0.6.1 still load.
  States from 0.6.0 or earlier still do not.
- Going back to 0.6.1 needs an uninstall, which can delete saves. Export your saves first.

## What's new

Most of these were checked on a PC only, not run on a device; the
[changelog](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/changelog.md) says which.

### Display

- **Two new screen layouts.** **Hybrid** shows the top screen large, with a small copy of it above
  the small bottom screen in a column beside it. **Custom** places each screen with six settings in
  percent of the display, set with the controller. Both are in Display → Screen layout.
- **Screen rotation.** Display → Screen rotation turns both screens upright (clockwise or
  counter-clockwise) or upside down, for book-style games held sideways. Touch turns with them.
- **Background colour.** Display → Background colour sets the colour around the screens: black,
  dark grey, grey, white or a custom #RRGGBB. **Pick colour** sets the custom one with the D-pad.
- **Picture effects.** Display → Picture effect: Sharpen, Vivid colours, Scanlines or Smooth edges,
  with a strength setting. Off by default; off costs nothing extra.
- **Your own picture shaders.** Picture effect → Custom shader draws the screens with your own GLSL
  file from the app's `shaders` folder. See
  [android](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/android.md).
- **0.5×, 4× and 5× rendering resolution.** 0.5× gives the lowest GPU load. 4× and 5× need much
  more GPU memory and can slow the game.
- **Screen gap and small-screen size.** Space between the screens in Stacked and Side by side, and
  the size of the small screen in Large top.
- **Screenshots hold both screens**, the top screen above the bottom one, in one PNG.
- **Frame interpolation no longer tears characters** and their outlines when a bone turns fast
  (seen in Ultra Moon). Upstream Zakuro.
- **Bloom glows when drawn scaled up**, instead of showing copies of bright things (Ultra Moon).
  Upstream Zakuro.

### Controls

- **D-pad mode.** Controls → D-pad mode → Circle Pad makes the D-pad drive the Circle Pad at full
  tilt, for games that only read the stick.
- **New button bindings:** Turbo, Blow into microphone, Fast-forward (toggle), Cycle layout, and
  Circle Pad or C-stick directions.
- **Controller fixes.** A on a text field opens the keyboard. Start no longer confirms a dialog
  such as Discard changes. L1/R1 scroll long dialogs. A acts on what had focus when it was pressed.

### Audio

- **Handheld microphone.** Audio → Microphone → Handheld microphone lets games that listen hear the
  handheld's microphone. Off by default. Blow into microphone works either way.

### Performance

- **Faster copies out of drawn pictures.** Games that copy a texture out of a drawn picture every
  frame do it on the GPU now. Upstream Zakuro.
- **Faster texture decoding**, tile by tile instead of texel by texel (**Fast texture decoding**,
  on by default).
- **Faster HD-pack lookups.** A texture that no pack picture can match skips hashing, and ETC1
  textures hash about 18% faster.
- **Less work per draw** on the CPU vertex path.

### Library and settings

- **Sort the library** by Last played, Name or Play time (Menu → Sort).
- **Updates and DLC.** Game files → Import update or DLC… adds a game's decrypted update, DLC or
  Download Play child.
- **Mods…** in Game files lists what a game's mods change and removes one part or all of them.
- **Cheats in a running game.** Pause menu → Settings… → Cheats… switches cheats at once.
  **Add cheat…** types a cheat in.
- **Camera picture.** Game files → Camera picture… chooses a picture, such as a QR code, for the
  game's cameras.
- **Dump textures** (Advanced) saves each new texture as a PNG named for a texture pack.
- **Play time is saved every minute**, so a crash loses at most a minute.
- **Settings are easier to use:** settings that apply only to another choice hide until it is
  picked (their values are kept), sliders move one unit per D-pad press, and descriptions are
  larger.

### Fixes

- Texture packs whose pictures have extra bytes after the end now import.
- On Android 11 and 12, importing cheats or settings, reading a driver and exporting frontend files
  could stop with an error. Fixed.
- A web page can no longer start a game through a link. Frontends and home-screen shortcuts still
  work.
- Clearer messages while an import runs and on the Storage screen.

## Not in this build

The stable APK has no network permission. Local wireless rooms, the in-app updater (Menu → Check
for updates…), importing a game from a folder with only a ROM, and Pin to home screen are in the
nightly build only.

## Known limits

- 0.5× falls back to 1× on a GPU that can't blit depth buffers. The log says so.
- The handheld microphone needs Android's microphone permission. Android asks the first time.
- Picture shaders are 2D only.
- One test device, an AYN Thor. Most new features were checked on a PC only.
- The known issues still apply, see
  [known issues](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/known-issues.md).

## Install

Download **Zakuroishi-0.7.0.apk** below and install over the existing app; do not uninstall first.
Requires 64-bit ARM, Android 11+ and Vulkan 1.3. New users: see the
[setup guide](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/android.md). Each game
needs separately built native game code; see the
[native library guide](https://github.com/Sekyleky/Zakuroishi/blob/android-handheld/docs/native-library.md).
Texture packs are optional.

## Verify

Check the APK against `SHA256SUMS.txt`, which comes with it. Signing certificate:
`CN=Zakuroishi Release, O=Zakuroishi`, SHA-256
`56d7615f627991436670380ccbb8b061b7bca717044d209607a98d44e4a82b0e`. No games, compiled game
libraries, saves, keys, firmware, texture packs or `Lossless.dll` files are distributed. Credits to
the upstream Zakuro, 3dsrecomp, libadrenotools, lsfg-vk, DXVK, pe-parse and volk projects and their
contributors.
