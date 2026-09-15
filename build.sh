#!/data/data/com.termux/files/usr/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-35
RESOURCE_SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-9
TOOLS_DIR=/data/data/com.termux/files/usr/bin
OUT="$PROJECT_DIR/build"
RES_COMPILED="$OUT/res-compiled.zip"
RES_APK="$OUT/resources.apk"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
TOOL_CLASSES="$OUT/tool-classes"
DELIVERY_DIR=/storage/emulated/0/Documents
APK_NAME=Bolsa-da-Hermione.apk

rm -rf "$GEN" "$CLASSES" "$DEX" "$TOOL_CLASSES" "$RES_COMPILED" "$RES_APK" \
    "$OUT/classes.jar" "$OUT/unsigned.apk" "$OUT/Cofre-Seguro-aligned.apk" \
    "$OUT/Cofre-Seguro.apk" "$OUT/$APK_NAME"
mkdir -p "$GEN" "$CLASSES" "$DEX" "$DELIVERY_DIR"
mkdir -p "$TOOL_CLASSES"

"$TOOLS_DIR/aapt2" compile --dir "$PROJECT_DIR/res" -o "$RES_COMPILED"
"$TOOLS_DIR/aapt2" link \
    -I "$RESOURCE_SDK_DIR/android.jar" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$GEN" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code 1 \
    --version-name 1.0.0 \
    --auto-add-overlay \
    -o "$RES_APK" -R "$RES_COMPILED"

find "$PROJECT_DIR/src" "$GEN" -type f -name '*.java' -print > "$OUT/sources.list"
javac --release 8 -encoding UTF-8 \
    -classpath "$SDK_DIR/android.jar" \
    -d "$CLASSES" \
    @"$OUT/sources.list"

javac --release 8 -d "$TOOL_CLASSES" "$PROJECT_DIR/tools/StripMethodParameters.java"
java -cp "$TOOL_CLASSES" StripMethodParameters "$CLASSES"

jar cf "$OUT/classes.jar" -C "$CLASSES" .
"$TOOLS_DIR/d8" --release --min-api 26 --lib "$SDK_DIR/android.jar" \
    --output "$DEX" "$OUT/classes.jar"

cp "$RES_APK" "$OUT/unsigned.apk"
jar uf "$OUT/unsigned.apk" -C "$DEX" classes.dex
"$TOOLS_DIR/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/Cofre-Seguro-aligned.apk"

if [ ! -f "$OUT/cofre-seguro-release.keystore" ]; then
    keytool -genkeypair -noprompt \
        -keystore "$OUT/cofre-seguro-release.keystore" \
        -storepass cofreseguro \
        -keypass cofreseguro \
        -alias cofreseguro \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Bolsa da Hermione, OU=Local, O=Bolsa da Hermione, L=Local, ST=SP, C=BR"
fi

"$TOOLS_DIR/apksigner" sign \
    --ks "$OUT/cofre-seguro-release.keystore" \
    --ks-pass pass:cofreseguro \
    --key-pass pass:cofreseguro \
    --out "$OUT/$APK_NAME" \
    "$OUT/Cofre-Seguro-aligned.apk"

"$TOOLS_DIR/apksigner" verify --verbose "$OUT/$APK_NAME"
cp "$OUT/$APK_NAME" "$DELIVERY_DIR/$APK_NAME"
printf 'APK criado: %s\n' "$OUT/$APK_NAME"
printf 'APK entregue em: %s\n' "$DELIVERY_DIR/$APK_NAME"
