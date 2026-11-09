# Contributing

Thanks for helping. Keep changes small and tested.

- **Build:** see [docs/development.md](docs/development.md) for the APK and native-library toolchains.
- **Host checks:** run `cargo test --release --workspace` before opening a pull request. CI runs the same.
- **Performance changes ship behind a setting**, off by default until measured, so they can be turned off.
- **Measurements come from a device** (the AYN Thor is the reference). Host or emulator timings are not evidence; say which device, driver and game.
- **No game data in commits:** no ROMs, `title.so` libraries, firmware, keys, saves or texture packs. Do not commit signing keys.
- **Reports:** use the issue templates; for security problems see [SECURITY.md](SECURITY.md).
- **License and credit:** contributions are MIT, like the rest of the repository. This project builds on
  [fearkov/zakuro](https://github.com/fearkov/zakuro) (MIT); keep its copyright notice and credit in [LICENSE](LICENSE) and the README.
