# Setup

## 1. Prerequisites

**Android Studio** (any recent version). Install it rather than fighting a
command-line SDK - you need the SDK, platform-tools, and a compatible JDK anyway.

**JDK 17 or 21.** Your system JDK is currently 26, which Gradle 8.7 / AGP 8.6 do
not support. Android Studio bundles a suitable JBR and uses it by default, so
building from the IDE avoids the problem. To build from the terminal:

```bash
# point Gradle at Android Studio's bundled JDK
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

**A GitHub personal access token.** karoo-ext is a public package but GitHub
Packages always requires authentication. Create a classic token with the
`read:packages` scope at https://github.com/settings/tokens, then add to
`~/.gradle/gradle.properties` (not the repo - keep it out of version control):

```properties
gpr.user=your-github-username
gpr.key=ghp_yourtokenhere
```

## 2. Build

```bash
cd karoo-classified
./gradlew :probe:assembleDebug    # hardware investigation app
./gradlew :app:assembleDebug      # the extension
./gradlew :app:test               # unit tests for the decoder
```

APKs land in `probe/build/outputs/apk/debug/` and `app/build/outputs/apk/debug/`.

Both modules are known to build cleanly with JDK 21 and Android SDK 35, and the
11 unit tests pass.

### Building without a GitHub token

If you would rather not create a PAT, build karoo-ext from source instead - the
project's `settings.gradle.kts` checks `mavenLocal()` first:

```bash
git clone --depth 1 https://github.com/hammerheadnav/karoo-ext.git
cd karoo-ext && echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :lib:publishToMavenLocal
```

That publishes `io.hammerhead:karoo-ext:1.1.9` locally and the build resolves it
with no credentials. This is how the APKs in this repo were produced.

## 3. Install on the Karoo

The Karoo 3 has no USB debugging toggle in the usual place - enable developer
access first.

**Enable developer mode:** Settings → About → tap the build number seven times,
then Settings → Developer options → enable **USB debugging**.

**Install over USB:**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb devices                                   # confirm the Karoo is listed
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If `adb devices` shows `unauthorized`, accept the prompt on the Karoo screen.

**Alternative - no cable:** the Hammerhead Companion app can sideload an APK from a
URL on Karoo 3. Useful if USB is awkward, but `adb logcat` is needed for the probe
anyway, so USB is better for Stage 1.

## 4. Stage 1 - run the probe

This is the part that needs your real hub, and the part I cannot do for you.

```bash
adb logcat -c                       # clear the buffer
adb logcat -s ClassifiedProbe       # leave this running in a terminal
```

Open **Classified Probe** on the Karoo.

### 4a. ANT+ access check

Runs automatically on launch, and on the "Check ANT+ access" button. It reports
whether ANT Radio Service, the ANT+ Plugins Service, and the ANT system feature
are present.

- **ANT Radio Service absent** → a sideloaded app cannot open an ANT+ channel on
  this device. Go to the BLE path.
- **ANT Radio Service present** → worth attempting, but not proof. Actually
  acquiring a channel needs the ANT Android SDK AAR (free registration at
  thisisant.com - it is not on Maven Central, and is the "missing file" ki2
  cannot redistribute). That is Stage 2.

### 4b. BLE capture

Wake the hub first - press the ring shifter so the axle is advertising, and keep it
awake, since these hubs sleep aggressively.

Press **Scan BLE (20s)**. The probe lists every advertiser with address, RSSI, name
and advertised service UUIDs, and auto-connects to anything named `*classified*`.
On connect it dumps the full GATT database and subscribes to every notifiable
characteristic.

**Then shift between ratios several times** while watching the log. Each
notification prints as `[timestamp] <characteristic-uuid> <hex bytes>`.

What you are looking for: a characteristic whose payload changes consistently and
reversibly with the ratio - most likely a single byte or bit flipping between two
values. Shift at least 4-5 times so a genuine correlation separates from noise.

Send me the log and I will write the decoder against the real bytes.

### If nothing is named "classified"

The axle may advertise under a different name or none at all. Note any unfamiliar
address with a strong RSSI (better than about -60 dBm means it is close), and we
can add a connect-by-address option to the probe.

## 5. Remove the Classified hub from the Sensors app FIRST

**Prerequisite, not optional.** If the hub is still paired in Karoo's Sensors app
from an earlier experiment, it keeps claiming the shifting role and your AXS rear
gear stays frozen regardless of what this extension does. The extension works by
staying *outside* Karoo's sensor arbitration, which only helps if the hub is not
inside it.

On the Karoo: **Sensors** → find the Classified hub → **Forget / Remove**.

Then confirm the AXS is healthy again before installing anything: Front Gear and
Rear Gear should track your shifts normally. That is the baseline the extension
must not regress.

## 6. Add the field to a ride profile

Once `app-debug.apk` is installed, on the Karoo: **Profiles** → pick a profile →
edit a page → add a data field → find **Classified** in the picker.

Place it **alongside** your existing Front Gear / Rear Gear fields, not instead of
them - confirming that both keep working with the extension running is the whole
point of Stage 3.

Both fields only stream while a ride screen is open - Karoo does not publish sensor
data to extensions when idle. `Classified` shows `--` until the hub is paired and
transmitting; `Rear Gear+` needs the derailleur to actually move, which for most
electronic groupsets means the drivetrain has to be turning.

## 7. Stage 3 - confirm nothing regressed

With the extension installed and the field on a page, ride/shift and verify that
native **Front Gear** and **Rear Gear** still track the AXS. If they do, the
sidestep works. If they do not, the extension is somehow still entering sensor
arbitration and that is a design problem, not a configuration one - tell me.

## Troubleshooting

**A new or renamed field does not appear in the picker.** Karoo caches each
extension's declared field list in `io.hammerhead.appstore`, and reinstalling the
APK does not refresh it. Restarting `io.hammerhead.appstore` and
`io.hammerhead.profileconfiguratorapp` is usually not enough either.

**Reboot the Karoo.** That reliably reloads `extension_info.xml`. Expect to do this
every time a `DataType` is added, removed or renamed - editing the code alone will
leave you looking at a stale picker and wondering why the build did nothing.

Existing fields already placed on a profile keep working across this, because they
are matched by `typeId`, not by display name.

**The Classified field does not appear in the picker.** Karoo discovers extensions
by scanning for services with the `io.hammerhead.karooext.KAROO_EXTENSION` intent
filter, usually on boot. Reboot the Karoo after installing.

If it still does not appear: this extension deliberately ships no launcher activity
(per the "no UI" requirement), which means it is never manually launched and stays
in Android's "stopped" state after install. That should not block an explicit
service bind, but if discovery does fail, adding a trivial launcher activity is the
first thing to try - the karoo-ext sample extension ships one.

You can confirm the service is visible to the system with:

```bash
adb shell dumpsys package com.krishnakb.karooclassified | grep -A5 KAROO_EXTENSION
```

**Check the extension is running:**

```bash
adb logcat -s KarooExtension:* Timber:*
```

Look for `extension classified [0.1] started by Karoo System`.
