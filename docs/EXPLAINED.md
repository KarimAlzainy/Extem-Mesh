# EXPLAINED — every file, in plain English

This document explains what each file in the project does, why it exists, and how to
defend it if someone asks. If you can explain the files in this order, you can explain
the whole project.

Read the three sections that matter for the science fair: **§3 the simulator**,
**§5 the Android app**, and **§6 the evidence**.

---

## 0. The one-paragraph version

EXTEM-Mesh is an offline emergency messaging prototype. Phones cannot reach the internet,
so they carry messages for each other over Bluetooth LE using "store and forward": a
message is stored on a phone until that phone meets another phone, then it is copied
across. Two ideas are compared against published baselines:

1. **urgency-ordered routing with limited copies** (instead of flooding), and
2. **quorum duty cycling** — the radio listens in a pattern where any two phones are
   guaranteed to overlap regularly, instead of listening all the time.

The repo contains **two implementations of the same protocol**: a simulation in
JavaScript (used for the measured numbers on the poster) and an Android app in Kotlin
(used for the real two-phone demo). Shared design, separate code.

---

## 1. Stage roadmap (be honest about what exists)

| Stage | What it adds | Status |
| --- | --- | --- |
| 1 | Real BLE link between two phones, permissions, foreground service | **built, needs a two-phone test** |
| 2 | Store-and-forward: Room database, summary vectors, TTL, CSV log | not started |
| 3 | Duty cycling (3 profiles) + battery logging | not started |
| 4 | AES-GCM encryption with a shared passphrase | not started |
| 5 | Full Compose UI (chat, nearby, store, settings, stats, SOS) | not started |
| 6 | Simulator sharing routing code with the app, ≥20 seeds, animation | not started |
| 7 | README + `docs/TEST_PLAN.md` | not started |

The simulator numbers in `results/` are **already measured** and are not affected by
the app stages. Nothing in this repo claims the app does something it does not do.

---

## 2. Root files

| File | What it is |
| --- | --- |
| `README.md` | The front door: what the project is, how to run the simulator, what the measured results are, what is still missing. |
| `package.json` | Node.js project description. `"type": "module"` means the JavaScript files use `import`/`export`. The `scripts` section gives short commands (`npm test`, `npm run demo`, `npm run experiments`). |
| `.gitignore` | Keeps build output (`node_modules`, `android/build`, `android/.gradle`, `local.properties`) out of git. |

---

## 3. The simulator (`src/`)

The simulator is the part that produced the poster numbers. It is a **discrete-time**
simulation: one tick = one second of "street time".

### 3.1 `src/core/rng.js` — reproducible randomness

A tiny pseudo-random generator (`mulberry32`). It is seeded, which means
**same seed → same experiment → same result**. This is why a judge can re-run the suite
and get our exact figures instead of "trust me". `uniform(rng, min, max)` converts a
0..1 random number into a real range (speeds, positions, message creation times).

### 3.2 `src/core/model.js` — the energy model

All physical assumptions live here in one object, `ENERGY`: battery size (4000 mAh),
current draw while asleep / awake / scanning / advertising / transferring, and the duty
cycle helpers.

> **Judge question: "where do these numbers come from?"**
> They are assumptions taken from vendor BLE datasheets, Android Battery Historian traces
> and published BLE duty-cycle studies. Because they are assumptions, they are named in
> one place and stress-tested in `src/cli/energy-table.js` (the sensitivity sweep). The
> conclusion must survive a ±50 % change in these numbers — that is exactly what
> `results/energy-sensitivity.csv` shows.

`radioEnergy({ duty, hours, ... })` turns "the radio listens X % of the time" into
"milliamp-hours consumed", which is then turned into "% of battery" and "% saved versus
listening continuously".

### 3.3 `src/core/policies.js` — the routing decision (the contribution)

One question: *"I just met a phone — which stored messages do I copy to it, and in which
order?"*

- `epidemic` — copy everything to everybody. Best possible delivery, worst overhead.
  Used as the **upper bound**.
- `spray` — classic Spray-and-Wait with a copy budget (default 8).
- `extem` — **our policy**: order messages by urgency (least time-to-live first), then by
  how few copies have been spent, and give copies only when the peer does not already
  have the message.

`copiesToGive()` and `initialCopies()` implement the copy budget. Limiting copies is what
keeps the radio from being busy all the time — and that is what makes duty cycling
affordable in the first place.

### 3.4 `src/sim/engine.js` — the world

Per tick it: (1) moves every phone, (2) finds pairs within BLE range, (3) checks whether
both radios are awake at that moment, (4) if yes, exchanges summary vectors and hands
over messages.

Key functions: `defaultConfig()` (all scenario settings), `isActive()` (is this phone's
radio awake now?), `quorumDuty()` (the quorum schedule: the radio listens in 5 of every 7
short slots, so any two phones overlap within a bounded time), `runTrial()` (one complete
run, returns messages delivered, copies sent, energy, delivery times).

Three scenarios:

- **district** — 60 phones, normal movement, messages created over time.
- **partition** — two groups that rarely meet: tests what happens when the network is cut.
- **fast** — sprint version used for quick re-checks.

### 3.5 `src/sim/hops.js` — honest multi-hop analysis

Takes the recorded contact trace and, **from the moment each message was created**,
computes the earliest possible arrival at every other phone using only contacts that
happen *before the message expires*. That is a time-respecting (temporal) shortest path.
Messages that cannot arrive in time are counted as unreachable — we do not credit a path
that only becomes possible after the message is dead. Output: hop buckets (1 hop,
2 hops, 3–5 hops, 6+, unreachable).

### 3.6 `src/cli/` — the commands

| File | Command | What it does |
| --- | --- | --- |
| `run.js` | `npm run experiments` | Runs all four stages: main comparison, duty sweep, sensitivity, fast scenario. Prints progress. |
| `experiments.js` | (library) | The experiment plan: which policies, which scenarios, which seeds (12 seeds main, more for sweep). The comment block at the top explains the **ablation logic** — why each baseline exists. |
| `report.js` | (library) | Reads `results/raw-runs.csv` and writes `results/REPORT.md`, the CSV tables and the SVG charts. Every number in the report is computed here — **nothing is typed by hand**. |
| `stats.js` | (library) | `mean`, `sd`, `ci95`, `compare` (permutation test). One run proves nothing; we report means with 95 % confidence intervals and test differences between policies. |
| `query.js` | `npm run query` | Quick ad-hoc averages straight from `results/raw-runs.csv`. Useful when someone asks "what was spray's PDR again?" |
| `demo.js` | `npm run demo` | A 60-second narrative of one message's life, computed with the same functions as the simulator (so it is not a cartoon). |
| `energy-table.js` | `npm run energy` | Standalone table: "what does 40 % duty cost?" plus the ±50 % sensitivity sweep. |

### 3.7 `tests/core.test.js`

`npm test` (which runs `node --test`, not `node --test tests/`, because Node 24 treats a
directory argument differently). Covers: the RNG is reproducible, the energy model
behaves (0 % duty costs nothing extra, 100 % duty costs the most), the copy budget is
respected, the quorum schedule guarantees coverage, the hop analysis is monotonic, and
the statistics helpers are correct.

---

## 4. `results/` — the evidence folder

| File | Meaning |
| --- | --- |
| `raw-runs.csv` | **The primary data.** One row per run (scenario, policy, seed, delivered, overhead, energy, latency...). Everything else is derived from this. |
| `summary.json` | Machine-readable summary of the same data. |
| `REPORT.md` | The written report, generated by `report.js` from `raw-runs.csv`. |
| `hop-buckets.csv` | Delivery probability grouped by hop distance. |
| `sweep.csv` | Duty-cycle sweep results (PDR and energy for each listening fraction). |
| `energy-sensitivity.csv` | The energy conclusion under ±25 % / ±50 % changes in the assumptions. |
| `statistical-tests.csv` | Permutation-test results for "is policy A really better than B?" |
| `charts/*.svg` | The three figures used on the poster (PDR by policy, PDR by hops, energy trade-off). |
| `run.err` | Standard-error output of the last suite run (kept so a crash could be diagnosed). |

Measured headline numbers (12 seeds, 226 runs total):

- EXTEM quorum (5,7): **84.5 % ± 0.6 PDR**, **4.71 % extra battery**, **31 % radio duty**,
  median delivery **610 s**, p95 **1462 s**.
- EXTEM continuous: 88.8 % ± 0.4 PDR, 12.7 % extra battery.
- Naive 35 % duty: 70.2 % ± 1.3 PDR, 5.1 % extra battery.
- Epidemic: 42.2 % ± 1.3 PDR, 13.6 % extra battery (flooding wastes the budget).

---

## 5. The Android app (`android/`)

> **What this app is, honestly:** a native Kotlin/Jetpack Compose app that opens a real
> Bluetooth LE link between two phones, exchanges text messages over it, and logs what
> happened to a CSV file. It is **Stage 1**. It does not yet store messages for later,
> it does not yet duty-cycle the radio, and it is not encrypted.

### 5.1 Build files

| File | What it does |
| --- | --- |
| `settings.gradle.kts` | Tells Gradle where plugins and libraries come from (`google()`, `mavenCentral()`), names the project, and includes the `:app` module. |
| `build.gradle.kts` (root) | Declares the two plugins the project uses: the Android Gradle Plugin **9.4.1** and the Compose compiler plugin **2.4.10**. `apply false` means "make available, each module decides". |
| `gradle.properties` | Gradle daemon memory and caching. |
| `local.properties` | **Machine-specific** path to the Android SDK (`E:\Android\SDK`). Never committed. |
| `gradlew.bat`, `gradlew`, `gradle/wrapper/*` | The Gradle wrapper: pins Gradle **9.6.0** so the project builds the same way on any machine. |
| `app/build.gradle.kts` | The app module: `namespace`, `compileSdk 37`, `minSdk 26`, `targetSdk 37`, Compose enabled, Java 17, and the dependency list (Compose BOM 2026.09.00, Material 3, activity-compose, coroutines). |

**Why these versions?** AGP 9.4 officially requires Gradle 9.6.0, build-tools 36, JDK 17
and supports compileSdk up to 37. Those are exactly what is installed on this machine.
AGP 9 also has **built-in Kotlin support**, so the old `kotlin-android` plugin is *not*
used anymore — only the Compose compiler plugin, whose version must match Kotlin 2.4.10.

### 5.2 `app/src/main/AndroidManifest.xml`

Declares everything the app is allowed to do:

- **Android 12+ (API 31+)**: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`.
  `BLUETOOTH_SCAN` carries `neverForLocation`, because we only look for our own service
  UUID and never use scan results to infer where the user is.
- **Android 11 and older**: the legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` plus
  `ACCESS_FINE_LOCATION` (limited to `maxSdkVersion="30"`, because scanning used to
  require location on those versions).
- `POST_NOTIFICATIONS` (Android 13+) and `FOREGROUND_SERVICE` +
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` (Android 14+) for the foreground service.
- `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>`
  so the app cannot be installed on a phone without BLE.
- The activity (launcher) and the service (`foregroundServiceType="connectedDevice"`).

### 5.3 Kotlin sources

All paths below are under `app/src/main/java/com/extem/mesh/`.

#### `MainActivity.kt`
The only screen host. It:
1. works out which permissions are needed for **this** Android version
   (`requiredPermissions()` — the three BLE permissions on 12+, location on 11-,
   plus notification permission on 13+),
2. asks for them with `RequestMultiplePermissions`,
3. shows `MeshScreen` and starts/stops `MeshService`.

It never touches Bluetooth itself. That separation is deliberate: the radio lives in a
service so it keeps working when the screen is off.

#### `core/NodeIdentity.kt`
Every phone gets an 8-hex-character node ID (`A1B2C3D4`) on first run, stored in
`SharedPreferences` so it survives restarts, plus helpers to convert it to/from the 4 raw
bytes used inside frames and advertisements. **It is a label, not a credential** — Stage 1
has no authentication, so an ID only means "some phone claims to be A1B2C3D4".

#### `core/EventLog.kt`
Writes `time,node,event,details` rows to `extem-events.csv` in the app's private files
folder, and keeps the last 80 lines in a `StateFlow` so the screen can show them. The log
never invents data: it records what the radio actually did, which is how you prove the
demo is real.

#### `core/MeshRepository.kt`
The single source of truth the UI observes: node ID, running state, status text, chat
messages, list of peers seen, and the **outgoing queue** of messages typed but not yet
sent. The service writes into it, Compose reads from it. Keeping it in one object means
the chat survives the screen being closed.

#### `protocol/MeshFrame.kt`
One logical message on the wire:

```
byte 0     kind     1 = HELLO, 2 = TEXT, 3 = BYE
bytes 1-4  sender   the 8-hex node ID as 4 raw bytes
bytes 5..  text     UTF-8
```

`HELLO` proves a link works and is never shown in the chat; `TEXT` is a real message;
`BYE` says "I have nothing more, you may close". The sender is carried inside the frame
because Bluetooth device addresses are random and unhelpful.

#### `protocol/ChunkCodec.kt`
Bluetooth LE moves a few dozen bytes per packet, so every frame is cut up:

```
byte 0  version (1)
byte 1  frameId (0..255)
byte 2  index   (0..total-1)
byte 3  total
bytes 4.. payload slice
```

`ChunkCodec.encode()` produces the chunks; `FrameAssembler` puts them back together and
returns the frame only when **all** chunks arrived in order (out-of-order and duplicates
are ignored). Why not send the whole message in one write? Android has no public API to
read a characteristic in pieces, and long writes behave differently on different phones.
Small numbered chunks are boring but they work everywhere.

#### `ble/BleConstants.kt`
All fixed numbers in one place: our random 128-bit service UUID
(`e7b10000-…`), the `TO_SERVER` (write) and `FROM_SERVER` (notify) characteristic UUIDs,
the standard descriptor UUID used to switch notifications on, the manufacturer ID
(`0xFFFF`, the documented "prototype" ID), the MTU we ask for (185), and
`packetSize(mtu)` which converts an MTU into usable payload bytes.

#### `ble/BleAdvertiser.kt`
Broadcasts two small packets: the **advertisement** contains only our service UUID (so
other phones can filter for us) and the **scan response** contains the 4-byte node ID.

> **Important design fact:** no message text is ever put in an advertisement.
> Advertisements are broadcast to anyone nearby and are limited to 31 bytes, so they are
> used as a handshake; messages travel over a real GATT connection (see
> `docs/HOW_IT_WORKS.md` and the background-limits section of `android/README.md`).

It uses `AdvertisingSetParameters` in **legacy mode** because that is the most widely
compatible mode on real phones, and reports start/failure through `AdvertisingSetCallback`.

#### `ble/BleGattServer.kt`
The "server" half of a link: it publishes our own GATT service with
`TO_SERVER` (write, acknowledged) and `FROM_SERVER` (notify + descriptor). When another
phone connects and writes chunks, it acknowledges each write, reassembles the frame and
hands it to the service. Replies are queued and sent as notifications **one chunk at a
time**, waiting for `onNotificationSent` before sending the next — that flow control is
what stops a queue of notifications from silently overflowing on the other phone.

#### `ble/BleScanner.kt`
Scans with a **filter on our service UUID**, so only phones running this app are
reported. The node ID is read from the scan-response manufacturer data. Each peer is
forwarded at most every 3 seconds (otherwise the callback fires tens of times per second
for the same phone and floods the log).

#### `ble/BleLink.kt`
The "client" half: a single, complete, readable exchange implemented as one suspend
function:

1. connect to the peer,
2. ask for a bigger MTU (185) and find our service and characteristics,
3. switch notifications on (write the descriptor),
4. write our frames chunk by chunk, waiting for each acknowledgement,
5. listen until the peer sends `BYE` (or a timeout),
6. disconnect.

It uses a `Channel<String>` of "steps" (connected / services_ok / notify_ok / write_ok /
bye ...) so the coroutine can *wait* for a callback without blocking, with
`withTimeoutOrNull` on every wait. Timeouts matter: a demo must not hang forever when a
phone walks out of range.

#### `service/MeshService.kt`
The only class that talks to the radio, and it is a **foreground service** (visible
notification) because Android suspends background apps after a few minutes — without the
notification the demo would stop exactly when the phones are far apart.

It starts all three radio pieces and applies the tie-break rule:

> **The phone with the alphabetically smaller node ID dials; the other one waits to be
> called.** Same rule on both phones → exactly one connection instead of two phones
> dialling each other at the same moment.

The exchange itself: the dialler sends `HELLO`, its queued messages, then `BYE`. On
receiving `BYE`, the other phone answers with `HELLO`, its own queued messages, `BYE`.
Both sides therefore get each other's messages over a single connection, and messages are
returned to the queue if the link did not complete (= nothing silently lost).

#### `ui/Theme.kt`
A small dark Material 3 colour scheme. Compose draws the whole UI, so the platform theme
in `res/values/themes.xml` only removes the action bar.

#### `ui/MeshScreen.kt`
The Stage 1 screen: node ID, status line, peers seen, start/stop, a message box with a
**Queue** button, the chat, and the last five event-log lines. It shows only data from
`MeshRepository` and `EventLog` — it never invents anything.

#### `res/` — resources
- `values/strings.xml` — the app name.
- `values/themes.xml` — window background and "no action bar" (Compose draws everything).
- `drawable/ic_launcher.xml` — the app icon: three mesh nodes joined by links (also used
  as the notification icon).

### 5.4 `android/README.md`

The build / install / two-phone test instructions, the permission table, and the honest
explanation of **Android background limits** (why the foreground service exists, what
Doze does, and what would happen without it).

---

## 6. `docs/` — the other documents

| File | Purpose |
| --- | --- |
| `HOW_IT_WORKS.md` | The plain-English protocol explanation: store-and-forward, summary vectors, copies, quorum duty cycling, and the honest limits. |
| `JUDGE_QA.md` | Prepared answers to the questions judges actually ask (including Arabic answers), and the "what this does NOT do" list. |
| `POSTER_EDITS.md` | The 13 exact edits that make the printed poster match the measured results. |
| `EXPLAINED.md` | This file. |

---

## 7. The three questions you must be able to answer

1. **"Why not just use the internet?"** — In a disaster the tower is down or overloaded.
   This is the situation where an offline mesh matters.
2. **"Why store-and-forward?"** — Two phones are almost never both in range at the same
   second. Storing a message and copying it whenever a contact happens is the only way to
   move data through a sparse, unplanned network.
3. **"Why not flood everything?"** — Flooding is the fastest way to deliver and the
   fastest way to empty a battery: measured 13.6 % extra battery for 42.2 % delivery,
   versus 4.71 % extra for 84.5 % delivery with urgency ordering plus quorum duty cycling.
