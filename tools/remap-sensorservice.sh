#!/usr/bin/env bash
#
# Re-derives the Karoo SensorService AIDL transaction table.
#
# Run this when a Karoo firmware update may have changed the internal interface
# the extension binds to. It pulls the service APK off a connected Karoo,
# decompiles the Binder class, and prints the transaction dispatch switch.
#
# See REMAPPING.md for what to do with the output.
#
# Usage: tools/remap-sensorservice.sh [output-dir]

set -euo pipefail

PKG="io.hammerhead.sensorservice"
OUT="${1:-build/remap}"

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "ERROR: '$1' not found. $2" >&2
        exit 1
    }
}

need adb   "Install Android platform-tools, or add it to PATH:
    export PATH=\"\$ANDROID_HOME/platform-tools:\$PATH\""
need jadx  "Install with: brew install jadx"

if [ -z "$(adb devices | sed '1d' | grep -w device || true)" ]; then
    echo "ERROR: no Karoo connected. Enable USB debugging and reconnect." >&2
    exit 1
fi

mkdir -p "$OUT"
echo "==> Locating $PKG on the device"
APK_PATH=$(adb shell pm path "$PKG" | head -1 | sed 's/package://' | tr -d '\r')
echo "    $APK_PATH"

echo "==> Reading installed version"
VERSION=$(adb shell dumpsys package "$PKG" | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r')
echo "    versionName=$VERSION"
echo "$VERSION" > "$OUT/version.txt"

echo "==> Pulling APK"
adb pull "$APK_PATH" "$OUT/sensorservice.apk" >/dev/null
echo "    $(du -h "$OUT/sensorservice.apk" | cut -f1)"

echo "==> Finding the Binder class returned by SensorService.onBind()"
jadx --single-class "$PKG.service.SensorService" \
     --single-class-output "$OUT/SensorService.java" \
     --no-res --no-debug-info "$OUT/sensorservice.apk" >"$OUT/jadx1.log" 2>&1 || true

# onBind returns a field; find that field's declared type.
FIELD=$(grep -A4 'IBinder onBind' "$OUT/SensorService.java" \
        | grep -oE 'this\.[A-Za-z0-9_]+' | head -1 | cut -d. -f2)
SIMPLE=$(grep -E "public +[A-Za-z0-9_.]+ +$FIELD;" "$OUT/SensorService.java" \
         | awk '{print $2}' | head -1)

# jadx needs a fully-qualified name; obfuscated fields are declared by simple
# name, so resolve it through the import list.
BINDER=$(grep -E "^import .*\.${SIMPLE};$" "$OUT/SensorService.java" \
         | head -1 | sed 's/^import //; s/;$//')
BINDER="${BINDER:-$SIMPLE}"

if [ -z "${BINDER:-}" ]; then
    echo "ERROR: could not identify the Binder class." >&2
    echo "Inspect $OUT/SensorService.java by hand - see REMAPPING.md step 4." >&2
    exit 1
fi
echo "    onBind returns field '$FIELD' of type '$SIMPLE' -> $BINDER"

echo "==> Decompiling Binder class $BINDER"
jadx --single-class "$BINDER" \
     --single-class-output "$OUT/Binder.java" \
     --no-res --no-debug-info "$OUT/sensorservice.apk" >"$OUT/jadx2.log" 2>&1 || true

echo "==> Verifying the interface descriptor"
DESCRIPTOR=$(grep -oE '"io\.hammerhead\.sensorservice\.[A-Za-z]+"' "$OUT/Binder.java" | head -1 | tr -d '"')
echo "    $DESCRIPTOR"

echo "==> Constructor parameter order (names the sub-binders from TX 14 upward)"
# Parameter names survive R8, so the constructor tells you which controller each
# writeStrongInterface(...) transaction hands back, in order.
grep -E "public +${SIMPLE}\(" "$OUT/Binder.java" | head -1 \
    | sed 's/.*(//; s/).*//' | tr ',' '\n' \
    | sed 's/^ *//' | awk 'NF {print "    " $NF}' || true

echo
echo "==> TRANSACTION DISPATCH (compare against REMAPPING.md 'Known-good table')"
echo "------------------------------------------------------------------"
awk '/boolean onTransact/,/^    }$/' "$OUT/Binder.java" \
    | grep -E 'case [0-9]+|case .*/\* [0-9]+ \*/|readString|readInt|readStrongBinder|CREATOR|writeStrongInterface' \
    | sed 's/^ *//' || true
echo "------------------------------------------------------------------"

echo
echo "==> DataSource signature (must still be id, name, dataType, device)"
jadx --single-class 'io.hammerhead.datamodels.timeseriesData.models.DataSource' \
     --single-class-output "$OUT/DataSource.java" \
     --no-res --no-debug-info "$OUT/sensorservice.apk" >"$OUT/jadx3.log" 2>&1 || true
grep -oE '"<init>", "\(Ljava/lang/String;Ljava/lang/String;[^"]*"' "$OUT/DataSource.java" | head -1 \
    || grep -oE 'public DataSource\([^)]*\)' "$OUT/DataSource.java" | head -1 \
    || echo "    (inspect $OUT/DataSource.java by hand)"

echo
echo "Done. Artifacts in $OUT/"
echo "Next: follow REMAPPING.md step 6 to update SensorServiceBinding.kt"
