# Re-mapping the Karoo SensorService interface

This extension reads shifting data through an **undocumented internal Karoo
interface**. Karoo firmware updates can change it without warning. When that
happens, the transaction numbers have to be re-derived.

This document is that procedure, written to be followed cold by someone who does
not remember any of this.

---

## 1. When you need this

- Both fields show `--` permanently, even mid-ride with the sensors awake.
- The log says the `hxsensorservice` version does not match the mapped one.
- A Karoo firmware update landed and you want to check before a ride.

**It will not misbehave silently.** The extension pins the `hxsensorservice`
version it was mapped against, and on a mismatch it sends nothing and calls no
transaction. A `--` field is the symptom; it never guesses.

## 2. Prerequisites

```bash
brew install jadx
brew install --cask android-commandlinetools
brew install openjdk@21

export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Connect the Karoo over USB with developer mode and USB debugging enabled
(Settings > About > tap build number 7x > Developer options), then confirm:

```bash
adb devices     # should list the Karoo as 'device', not 'unauthorized'
```

## 3. Run the extraction

```bash
./tools/remap-sensorservice.sh
```

It pulls `hxsensorservice.apk` off the Karoo, decompiles the Binder class and
prints the transaction dispatch. Artifacts land in `build/remap/`. Takes about a
minute, and **only reads** from the device - nothing is installed or modified.

## 4. Compare against the known-good table

**Reference version: `4.215.1-b362ac0378`**
(Karoo 3 / `k24`, Android 12, ANT Radio Service 4.15.20)

Binder descriptor: `io.hammerhead.sensorservice.SensorServiceAIDL`

| TX | Arguments | Meaning |
|---:|---|---|
| 1 | String id, int scanType, IBinder listener | startScan |
| 2 | String id, int scanType | stopScan |
| 3 | String id, Device, IBinder listener | connectDevice |
| 4 | String id | disconnectDevice |
| 5 | Device | device action |
| 6 | String id, Device, IBinder listener | connection-state listener |
| 7 | String id | remove connection-state listener |
| 8 | String id, DataSource, IBinder listener | per-device data subscribe |
| 9 | String id | unsubscribe |
| 10 | String id, Device, IBinder listener | device detail listener |
| 11 | String id | remove device detail listener |
| **12** | **String id, IBinder listener** | **raw ANT message stream - what we use** |
| **13** | **String id** | **stop the ANT stream** |
| 14-19 | - | sub-binders: power, shutdown, trainer, light, tirePressure, shifter |

## 5. What this extension actually depends on

Only three things. Everything else in the table is context.

**1. TX 12 and TX 13.** TX 12 registers a listener that receives every ANT message
the Karoo is hearing; TX 13 stops it. In the decompiled dispatch, TX 12 is the
transaction that reads a `String` and a `readStrongBinder()` and nothing else, and
whose listener then receives payloads typed `AntMessage`. TX 8 also takes a
listener, but reads a `DataSource.CREATOR` first - that is how to tell them apart.

**2. The listener interface** `io.hammerhead.aidlrx.IParcelableListener`:

```
TX 1  onNext(String id, String className, byte[] payload, boolean flag)   sync, expects writeNoException
TX 2  onError(int code, String message)                                   oneway
TX 3  onComplete()                                                        oneway
```

We implement this ourselves as a plain `Binder`. If these numbers move, the
extension stops receiving anything.

**3. The `AntMessage` parcel layout:**

```
int     messageId          0x4E = ANT broadcast data
byte[]  payload            int length, then bytes, padded to a 4-byte boundary
long    timestamp
```

with the ANT payload being `channel, 8 data bytes, flag, 4-byte channel ID, RSSI`.
The channel ID at offset 10 is what identifies the sending device.

**No reflection, and no `DataSource`.** An earlier design used TX 8 with a
`DataSource` parcelable, which would have needed classes loaded from the service's
classloader. The raw-stream route avoids that entirely - everything is decoded with
arithmetic on a byte array. If you find notes elsewhere referring to `DataSource`,
they describe the abandoned approach.

## 6. Ignore the obfuscated names

Class names like `n9.d` and `e6.g` change on **every** Karoo build. Nothing here
depends on them: transactions are addressed by number, and the only string
identifiers we use are the two interface descriptors, which are stable. The script
resolves the obfuscated Binder class automatically.

## 7. Update the code

Everything lives in one companion object in
`app/src/main/kotlin/com/krishnakb/karooclassified/transport/ShiftingStream.kt`:

```kotlin
const val MAPPED_VERSION = "4.215.1-b362ac0378"

private const val TX_ANT_STREAM = 12
private const val TX_ANT_STREAM_STOP = 13
private const val LISTENER_TX_NEXT = 1
private const val DESCRIPTOR = "io.hammerhead.sensorservice.SensorServiceAIDL"
private const val LISTENER_DESCRIPTOR = "io.hammerhead.aidlrx.IParcelableListener"
```

Change only what actually moved. If the transactions are unchanged and only the
version string differs, update `MAPPED_VERSION` alone - the pin is a tripwire, not
evidence that anything broke.

## 8. Rebuild and verify

```bash
./gradlew :app:assembleDebug :app:test
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s ShiftingStream ClassifiedExtension
```

Expect `Bound io.hammerhead.sensorservice.SensorServiceAIDL`. Then on the Karoo,
open a ride screen (data does not stream when idle) and check both fields:

- **Classified** tracks the hub's ratio.
- **Rear Gear+** tracks the derailleur. Spin the cranks - most electronic
  derailleurs will not complete a shift with the drivetrain stationary.

The `probe` app is the fallback diagnostic if something is off: it can dump the raw
ANT stream, so you can see whether messages are arriving at all and what they say.
It is not installed by default - build and sideload `:probe` when needed.

Two platform behaviours worth knowing before you debug the wrong thing:

- **Karoo calls `startStream`, `startView`, or both**, and which one is not
  predictable. Both fields implement both paths for this reason. A field that only
  implements `startStream` silently freezes at its last value whenever Karoo
  happens to call only `startView`.
- **A sensor low on battery drops off the air**, and the field correctly shows
  `--`. This looks exactly like the extension failing. Check for raw ANT traffic
  from that device before assuming the code is at fault:
  `adb logcat -d | grep -oE "[0-9]+-34-[0-9]+" | sort | uniq -c`

## 9. If it is unrecoverable

If the raw ANT stream transaction disappears entirely:

1. **Ask Hammerhead to support two concurrent shifting sensors.** Wahoo already
   does; the hardware plainly supports it, since Karoo receives both devices
   simultaneously today. This is the fix that does not rot.
2. **Check whether TX 8 still works** - per-device subscription via a `DataSource`
   carrying a `Device` was the other viable route, and is worth re-examining
   before giving up. It needs parcelable classes loaded by reflection from the
   service's classloader.
3. **Leave the version pin in place.** Fields show `--` and nothing is called,
   which is the safe state.
4. **Accept the original trade-off**: pair the hub and lose the derailleur's gear
   fields, or drop the hub and lose the ratio display.

## Appendix: doing it by hand

If the script breaks:

```bash
# 1. Pull the service
adb pull $(adb shell pm path io.hammerhead.sensorservice | sed 's/package://' | tr -d '\r') ss.apk

# 2. Record the version you are mapping against
adb shell dumpsys package io.hammerhead.sensorservice | grep versionName

# 3. Find which field onBind() returns, and resolve its type via the imports
jadx --single-class io.hammerhead.sensorservice.service.SensorService \
     --single-class-output SensorService.java ss.apk
grep -A4 'IBinder onBind' SensorService.java     # e.g. returns this.f3148e
grep 'f3148e;' SensorService.java                # e.g. 'public d f3148e;'
grep '^import .*\.d;' SensorService.java         # e.g. 'import n9.d;'

# 4. Decompile that class and read the dispatch
jadx --single-class n9.d --single-class-output Binder.java ss.apk
awk '/boolean onTransact/,/^    }$/' Binder.java
```

The transaction you want takes a `String` and a `readStrongBinder()`, reads nothing
else, and delivers `AntMessage` payloads.
