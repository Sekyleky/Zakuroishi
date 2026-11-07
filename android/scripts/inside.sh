#!/usr/bin/env bash
set -euo pipefail
cd /src
out=${1:?output filename required}
[[ "$out" =~ ^zakuro-android-test-[a-zA-Z0-9-]+\.apk$ ]] || exit 2
test -f crates/zakuro-android/Cargo.toml
test -f Cargo.lock
mkdir -p /work /keys
# Serialize access to the shared target directory and signing key.
exec 9>/work/build.lock
flock 9
rustc +1.95.0 --version
cmake -S android/third_party/libadrenotools -B /work/adrenotools -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static \
    -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
cmake --build /work/adrenotools --parallel 4
cargo +1.95.0 build --release --locked --target aarch64-linux-android \
    -p zakuro-android --lib --features zakuro-gpu/vulkan
"$NDK_BIN/llvm-strip" -o /work/libzakuro_android.so \
    "$CARGO_TARGET_DIR/aarch64-linux-android/release/libzakuro_android.so"
bash /src/android/scripts/make_apk.sh /work/libzakuro_android.so /work/signed.apk /keys/test.p12
# Signing and replacement happen on Linux storage; the bind mount gets a new file.
test ! -e "/out/$out"
cp --no-clobber /work/signed.apk "/out/$out"
# make_apk.sh's umask 077 protects the key, not the APK, and the host reads /out as another user.
chmod 644 "/out/$out"
(cd /out && sha256sum "$out" > "$out.sha256")
