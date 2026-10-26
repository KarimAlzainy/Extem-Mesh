# Android prototype (real phones)

**Status: written, not compiled.** There is no Android SDK on the machine this repo
was generated on, so these files have never been built or run by me. Treat them as a
precise starting point, not as a finished app. Everything in `src/` (the simulator) is
tested and runnable; everything in this folder must be verified on hardware.

That distinction matters for the project: **never put a number on the poster that came
from this folder until you have run it on three phones.**

## Files

| File | Role |
|---|---|
| `MeshNode.kt` | Pure protocol logic: buffer, TTL, Spray-and-Wait copies, quorum schedule. No Android imports — unit-testable with `./gradlew test`. |
| `Crypto.kt` | AES-256-GCM payload encryption, ECDH + HKDF helpers. |
| `BleTransport.kt` | Advertising, scanning, GATT server/client, connect → exchange → disconnect. |
| `MeshService.kt` | Foreground service, 1 s tick, quorum gating, and a **counted** duty-cycle measurement. |
| `MainActivity.kt` | Start the service, queue a test message, watch the log. |
| `AndroidManifest.xml` | Permissions and the `connectedDevice` foreground service type. |

## Build it

1. Android Studio → New Project → **Empty Views Activity** → Kotlin → package `org.extem.mesh`.
2. `minSdk 24`, `targetSdk 34`.
3. Copy the six files into `app/src/main/java/org/extem/mesh/` (the manifest replaces `app/src/main/AndroidManifest.xml`).
4. Add to `app/build.gradle.kts`: `implementation("androidx.appcompat:appcompat:1.7.0")`.
5. Run on a real phone. On Android 12+ accept the "Nearby devices" prompt; on Android 13+ allow notifications.
6. Disable battery optimisation for the app (`adb shell dumpsys deviceidle whitelist +org.extem.mesh`), otherwise Android will kill the service overnight and your battery test will be meaningless.

## The 3-phone test (this is your real-data experiment)

You need **three Android phones**. Two is not enough — you cannot demonstrate a relay
with two.

| Step | What to do | What to record |
|---|---|---|
| 1 | Install on A, B, C. Note each node id shown in the log. | node ids |
| 2 | Start the mesh on all three. | start time |
| 3 | Put A and C in two rooms ~60 m apart, B in a pocket. | distance |
| 4 | From A, queue a message to C. | send time |
| 5 | Walk B from A's room to C's room through the corridor. | relay time |
| 6 | When C shows "Message received", write down the time. | delivery time |

Repeat 10 times per walking route. Report **delivered ÷ sent** and the **median
delivery time**. That is a real PDR and a real latency for a 2-hop chain, and it is
more credible than any simulation number.

## The battery test (12 hours, this is the 3.8% claim)

Run it overnight, phone idle, screen off, **same phone model** for both arms:

1. **Control:** app not installed. `adb shell dumpsys batterystats --reset` at 20:00,
   then `adb shell dumpsys batterystats --charged` at 08:00.
2. **Mesh:** app installed and running. Same procedure, same starting charge (100%).
3. From the dump, read `Estimated power use (mAh)` and subtract. The difference is
   your real 12-hour overhead.

Record the phone model and Android version next to the number. A judge will ask.

## Feeding real numbers back into the simulator

Open `src/core/model.js` and replace the constants with what you measured:

```js
deepSleepMa: 12,   // <- control phone idle current
awakeMa: 45,       // <- mesh phone idle current while listening
scanRadioMa: 5.5,  // <- (mesh current) - (awake current)
```

Then `npm run report` regenerates everything. If the real duty cycle from
`MeshService.measuredDuty()` is not ~31%, the quorum schedule is not doing what the
simulator assumes — that is a finding, not a failure, and it belongs in the paper.

## Known limitations (say these before a judge finds them)

- One GATT connection at a time; the write loop should be chained on callbacks.
- No identity signatures yet, so a malicious node inside the mesh could forge a sender.
- The demo uses a pre-shared passphrase; `Crypto.sharedSecret` is the upgrade path.
- Tested design targets Android only. iOS blocks background BLE advertising, so a
  cross-platform claim would be false.
- Some Chinese Android skins kill background services aggressively; mention the
  battery-optimisation setting in the methodology.

## Ethics

Only test with classmates who agreed, only send your own test messages, and never use
this in a real emergency. It is a school prototype, not a life-safety system.
