# EXTEM-Mesh — Android app (Stage 1)

Native Android app (Kotlin + Jetpack Compose, minSdk 26) that opens a **real Bluetooth LE
link between two phones** and exchanges text messages over it.

Stage 1 is deliberately small: prove the radio link, prove the permissions are right,
prove it still works with the screen off. Store-and-forward, duty cycling, encryption and
the full UI come in later stages.

---

## 1. What Stage 1 does (and does not do)

**Does**

- Advertises a custom 128-bit service UUID and scans for it.
- Puts the node's short ID in the **scan response** manufacturer data (`0xFFFF`) — metadata
  only. **No message text is ever placed in an advertisement.**
- Connects over GATT, exchanges chunked frames in both directions, disconnects.
- Runs scanning + advertising + serving in a **foreground service** with a notification.
- Writes an honest event log to `extem-events.csv` (app-private storage).

**Does not (yet)**

- Store messages for later (Stage 2). If a link fails, the message stays in the queue.
- Duty-cycle the radio (Stage 3). The radio listens continuously in Stage 1.
- Encrypt anything (Stage 4). Messages are plaintext on the link.
- Have more than one screen (Stage 5).

---

## 2. Requirements

| Item | Version used here |
| --- | --- |
| JDK | 17 (Temurin 17.0.20.1) |
| Gradle | 9.6.0 (via `gradlew`) |
| Android Gradle Plugin | 9.4.1 |
| Kotlin / Compose compiler | 2.4.10 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| Android SDK location | `E:\Android\SDK` (in `local.properties`) |
| Phone requirement | Android 8.0+ with BLE **advertising** support (almost all phones from 2017 on) |

> AGP 9.4 needs Gradle **9.6.0**, build-tools **36.0.0**, JDK **17+** and supports
> compileSdk up to **37**. AGP 9 also has built-in Kotlin support, so the old
> `kotlin-android` plugin is not used at all.

---

## 3. Build

From this folder:

```bat
gradlew.bat :app:assembleDebug
```

The APK appears at:

```
app\build\outputs\apk\debug\app-debug.apk
```

Alternative: open the `android` folder in Android Studio (it will use the bundled JBR)
and press Run.

---

## 4. Install on two phones

Enable **Developer options → USB debugging** on both phones, connect one, then:

```bat
E:\Android\SDK\platform-tools\adb.exe devices
E:\Android\SDK\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

(Two phones, one at a time — or use `adb -s <serial> install ...` when both are connected.)

---

## 5. Tests

### 5.1 Already verified here (emulator, virtual Bluetooth — not radio)

The app was built and run on **two API 37 emulators** that talked to each other through the
Android Emulator's virtual Bluetooth stack. Full logs and screenshots are in
`../evidence/emulator/`. What those logs prove:

- advertising starts, scanning finds the peer by service UUID and reads its node ID from the
  scan response;
- a GATT connection is made, the MTU is negotiated, notifications are enabled;
- `HELLO` / `TEXT` / `BYE` frames travel **both ways** in one connection, chunked, and both
  phones end up with both messages in the chat;
- the smaller-ID-dials rule produced exactly one connection (client = `18B06623`,
  server = `ABDF5300`);
- with the app sent to the background, scanning and linking kept running and the service
  stayed `isForeground=true`;
- "Stop mesh" shuts everything down and writes `service,stop` / `server,stopped` /
  `advertise,stopped`.

What the emulator **cannot** prove: real range, real RSSI, real battery cost, and how vendor
Bluetooth stacks (Samsung, Xiaomi, …) behave. That is what the next test is for.

### 5.2 Two-phone test (exact steps)

1. Install the app on **both** phones and open it. Each shows a different `node A1B2C3D4`
   style ID. Write both IDs down.
2. On each phone tap **Allow Bluetooth** and grant **Nearby devices** (Android 12+).
   On Android 13+ also allow **notifications** (needed for the foreground service notice).
3. Turn **Bluetooth on** on both phones. Keep both phones unlocked and the app open.
4. Tap **Start mesh** on both. A permanent notification "EXTEM-Mesh · node …" appears.
5. Put the phones within ~1 m of each other.
6. Within a few seconds each phone's **Status** shows `Linked with <other id>` and the
   event log shows `scan_peer`, `link_ok` (on the dialling phone) or `link_in`/`link_out`
   (on the other one).

   > Which phone dials? The one with the **alphabetically smaller node ID** dials; the
   > other one waits to be connected. That is the tie-break rule, and it is why you see
   > different log lines on the two phones.
7. Type a message on **phone A**, tap **Queue**. Do the same on **phone B** with a
   different message.
8. Wait for the next link (immediately, if the phones are already in range, otherwise
   within ~15 s of the next scan hit). Both messages appear in **both** chats.

   Expected: phone A's chat shows both messages (its own on the right, B's on the left),
   and the same on phone B.
9. **Background test:** queue a message (step 7), then press the home button on both phones
   and lock one of them. The message still goes through within ~15 s, because the work
   happens in the foreground service and not in the screen.
10. Tap **Stop mesh** to shut the radio down. The notification disappears.

#### If it does not link

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Log shows `scan_peer` on both, but no `link_ok` | Both IDs are equal (same APK installed once and the ID copied via backup) | Clear app data on one phone, or check the IDs differ |
| `advertise … start failed status=…` | Bluetooth off, or the phone does not support BLE advertising | Enable Bluetooth; try another phone |
| Nothing in the log at all | Bluetooth permissions denied | Settings → Apps → EXTEM-Mesh → Permissions → Nearby devices |
| `link_fail` repeatedly | Phones too far apart, or one is asleep with battery saver | Move closer than 1 m, disable battery optimisation for the app |
| Link works, message missing | The message was queued **after** the exchange finished | Wait for the next link — Stage 1 has no retry timer of its own |

---

## 6. Reading the evidence

The app writes `extem-events.csv` in its private storage. To pull it:

```bat
E:\Android\SDK\platform-tools\adb.exe shell run-as com.extem.mesh cat files/extem-events.csv
```

Rows are `time,node,event,details`; the events are `service`, `advertise`, `scan`,
`scan_peer`, `link_in`, `link_out`, `link_ok`, `link_fail`, `msg_in`, `error`.
These rows are the proof of what actually happened — use them, not memory, when writing
the report.

---

## 7. Why a foreground service? (Android background limits, plainly)

Android does not let an app keep using Bluetooth forever in the background:

- **Android 8+ (API 26):** background execution limits. Once the app leaves the
  foreground, scans and timers are throttled or stopped.
- **Android 12+ (API 31):** apps cannot start a foreground service from the background,
  and starting one from a visible activity is fine. `BLUETOOTH_*` permissions are runtime
  permissions.
- **Android 13+ (API 33):** the app needs `POST_NOTIFICATIONS` for its foreground
  notification to be visible.
- **Android 14+ (API 34):** every foreground service must declare a **type**; ours is
  `connectedDevice`, which is exactly the type for talking to nearby devices, and it
  requires `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- **Doze / App Standby:** with the screen off for a long time, the system batches work.
  A foreground service with an ongoing notification is the supported way to keep
  scanning and advertising, but the user can still kill it, and aggressive vendor battery
  managers can too.

That is why stopping the notification stops the mesh: the notification **is** the
permission to keep the radio alive. Stage 3 will add a visible on/off switch and battery
logging so the cost of this choice is measured rather than assumed.

---

## 8. How a link works (Stage 1 protocol)

```
phone A (smaller ID, dials)                    phone B (waits, serves)
  scan -> sees B's service UUID + node ID
  connect -------------------------------------> onConnectionStateChange
  discover services
  request MTU 185
  write CCCD (notifications on) ----------------> onDescriptorWriteRequest
  write chunk 0..n  (HELLO)     ----------------> reassemble -> onFrame
  write chunk 0..n  (TEXT ...)  ----------------> chat message
  write chunk 0..n  (BYE)       ----------------> answer: HELLO + own messages + BYE
  receive notifications  <----------------------- chunk 0..n  (notify, flow-controlled)
  on BYE -> disconnect
```

Frames are chunked because BLE packets are small (20 bytes before MTU negotiation, ~182
after). Chunk header: `version, frameId, index, total` (4 bytes), so the receiver can
rebuild a frame from fragments, ignore duplicates, and know when a frame is complete.

---

## 9. File map

See `../docs/EXPLAINED.md` §5 for a plain-English description of every Kotlin file.
