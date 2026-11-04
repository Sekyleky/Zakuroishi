# Frame generation sources

Nothing from Lossless Scaling is in this directory or in the APK. The user imports their own
`Lossless.dll` at run time with the file picker in Performance settings. `translate.cpp` reads DXBC
resources 255-302 from it on the device and keeps the SPIR-V in app-private storage; the app's copy of
the DLL is deleted right after. Resources 303-400 of the DLL are never read. Not affiliated with
Lossless Scaling or its developer.

The translated shaders come only from the user's DLL. `framegen/` holds no shader data; `p_mipmaps` and
the rest are loaded by name from that storage.

| Directory | Source | Licence |
|---|---|---|
| `framegen/` | [GameNative/lsfg-vk-android](https://github.com/GameNative/lsfg-vk-android) `d33cace` (lsfg-vk 1.0.0 `7113d7d` plus Android patches; the `v2.0.0-dev` tag is not in its history). Performance graph only. | MIT, `LICENSE.md` |
| `translate.cpp` | id table and binding renumbering from the same repo, `src/extract/` | MIT |
| `dxbc/src` | [DXVK](https://github.com/doitsujin/dxvk) v2.6.2 `src/dxbc`, `src/spirv`, parts of `src/util`. lsfg-vk's own `PancakeTAS/dxbc` subset no longer exists online. `dxbc/src/dxvk/` and `util/log/log.h` are stand-ins written here; `dxbc_options.cpp` is reduced to its default constructor. | zlib, `dxbc/LICENSE` |
| `pe-parse/` | [trailofbits/pe-parse](https://github.com/trailofbits/pe-parse) `31ac596` | MIT, `pe-parse/LICENSE` |
| `volk/` | [zeux/volk](https://github.com/zeux/volk) `be3dbd4` | MIT, `volk/LICENSE.md` |
| `spirv/` | [SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers) as pinned by DXVK v2.6.2 | Khronos MIT-style, `spirv/LICENSE` |
