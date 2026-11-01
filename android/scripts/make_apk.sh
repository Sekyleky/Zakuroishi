#!/usr/bin/env bash
set -euo pipefail
engine=${1:?native library} out=${2:?output APK} keystore=${3:?test keystore}
app=/src/android/app
manifest="$app/src/main/AndroidManifest.xml"
java="$app/src/main/java"
[[ -f "$manifest" ]] || { manifest="$app/AndroidManifest.xml"; java="$app/java"; }
test -f "$manifest"
test -d "$java"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" "$work/lib/arm64-v8a"
# Only license notices are bundled; game files and driver packs remain user supplied.
"$BUILD_TOOLS_BIN/aapt2" compile --dir "$app/src/main/res" -o "$work/resources.zip"
"$BUILD_TOOLS_BIN/aapt2" link -o "$work/unsigned.apk" --manifest "$manifest" -I "$ANDROID_JAR" -A "$app/src/main/assets" "$work/resources.zip"
mapfile -t sources < <(find "$java" -name '*.java' | LC_ALL=C sort)
((${#sources[@]} > 0))
javac -nowarn --release 11 -classpath "$ANDROID_JAR" -d "$work/classes" "${sources[@]}"
mapfile -t classes < <(find "$work/classes" -name '*.class' | LC_ALL=C sort)
"$BUILD_TOOLS_BIN/d8" --min-api 30 --lib "$ANDROID_JAR" --output "$work/dex" "${classes[@]}"
cp "$engine" "$work/lib/arm64-v8a/libzakuro_android.so"
for library in libadrenotools.so libhook_impl.so libmain_hook.so libfile_redirect_hook.so libgsl_alloc_hook.so; do
    mapfile -t found < <(find /work/adrenotools -type f -name "$library")
    ((${#found[@]} == 1))
    "$NDK_BIN/llvm-strip" -o "$work/lib/arm64-v8a/$library" "${found[0]}"
done
(cd "$work/dex" && zip -q "$work/unsigned.apk" classes.dex)
(cd "$work" && zip -q -r -0 unsigned.apk lib)
"$BUILD_TOOLS_BIN/zipalign" -P 16 -f 4 "$work/unsigned.apk" "$work/aligned.apk"
umask 077
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -storetype PKCS12 -keystore "$keystore" -storepass android \
        -keypass android -alias zakuro-android-test -keyalg RSA -keysize 4096 \
        -validity 10000 -dname 'CN=Zakuro Android Test'
fi
"$BUILD_TOOLS_BIN/apksigner" sign --ks "$keystore" --ks-pass pass:android \
    --ks-key-alias zakuro-android-test --out "$out" "$work/aligned.apk"
"$BUILD_TOOLS_BIN/apksigner" verify --verbose --print-certs "$out"
"$BUILD_TOOLS_BIN/zipalign" -c -P 16 4 "$out"
"$BUILD_TOOLS_BIN/aapt" dump badging "$out" > "$work/badging"
grep -q "package: name='io.github.zakuro.android'" "$work/badging"
grep -q "launchable-activity: name='io.github.zakuro.android.Launcher'" "$work/badging"
unzip -Z1 "$out" > "$work/entries"
grep -qx 'lib/arm64-v8a/libzakuro_android.so' "$work/entries"
if grep '^assets/' "$work/entries" | grep -Ev '^assets/licenses/(ADRENOTOOLS|LINKERNSBYPASS|ZAKURO)\.txt$'; then
    echo 'Unexpected bundled assets' >&2; exit 1
fi
for library in libadrenotools.so libhook_impl.so libmain_hook.so libfile_redirect_hook.so libgsl_alloc_hook.so; do
    grep -qx "lib/arm64-v8a/$library" "$work/entries"
done
