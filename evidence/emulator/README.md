# Emulator link evidence — 2026-10-27

**What this is:** the event logs and screenshots from a real run of the Stage 1 app on **two
Android emulators** (`Medium_Phone_API_37.0`, API 37) that talked to each other over the
emulator's **virtual Bluetooth stack** (the Android Emulator's `netsim` packet streamer).

**What this is *not*:** it is not radio. No Bluetooth signal was transmitted, no antenna was
involved, and the range/RSSI values are invented by the emulator (`rssi=20` for every
packet). This proves the **app logic, GATT service, chunking, permissions, foreground
service and CSV logging** work end to end. It does **not** prove that two physical phones
will link at a given distance — that still needs the two-phone test in
`../../android/README.md` §5.

---

## Setup

| Item | Value |
| --- | --- |
| APK | `app-debug.apk` (versionName `0.1.0-stage1`), installed with `adb install -r` |
| Emulator A | serial `emulator-5554`, node **ABDF5300** (took the *server* role) |
| Emulator B | serial `emulator-5556`, node **18B06623** (took the *client* role) |
| Permissions | granted with `adb shell pm grant` (BLUETOOTH_SCAN / ADVERTISE / CONNECT, POST_NOTIFICATIONS) |
| Start | "Start mesh" tapped in the UI on both (the service is `exported=false`, so `am start-foreground-service` from adb is refused — that is intended) |
| Messages | "Hello from ABDF5300" queued on A, "Hello from 18B06623" queued on B, **before** starting the mesh |

The tie-break rule picked B to dial because `18B06623 < ABDF5300` alphabetically, which is
exactly what the logs show: B is the client, A is the server.

## Result (both links completed)

Node **18B06623** (client), from `node-18B06623-extem-events.csv`:

```
02:12:39.771  scan_peer   peer=ABDF5300 rssi=20
02:12:40.279  client      connected to 43:15:F0:7D:52:51
02:12:42.480  client      mtu=517
02:12:42.592  client      tx HELLO (0 chars)
02:12:42.638  client      tx TEXT (19 chars)
02:12:42.682  client      tx BYE (0 chars)
02:12:42.703  client      rx HELLO from ABDF5300
02:12:42.719  client      rx TEXT from ABDF5300
02:12:42.721  client      rx BYE from ABDF5300
02:12:42.736  link_ok     peer=ABDF5300 sent=1 received=1
```

Node **ABDF5300** (server), from `node-ABDF5300-extem-events.csv`:

```
02:12:39.661  scan_peer   peer=18B06623 rssi=20
02:12:40.382  server      peer connected (67:45:37:11:21:9C)
02:12:42.583  server      mtu=517
02:12:42.635  server      notifications enabled
02:12:42.702  server      rx HELLO from 18B06623
02:12:42.741  server      rx TEXT from 18B06623
02:12:42.791  server      rx BYE from 18B06623
02:12:42.799  link_out    peer=18B06623 sent=1
```

Both chats then showed **both** messages (`link-5554-server.png`, `link-5556-client.png`), and
both logs show later automatic re-links (`link_ok … sent=0 received=0`) every ~15 s while the
phones stayed in range.

**Background check:** after pressing HOME on both emulators, the log kept adding
`scan_peer` / `server, peer disconnected` lines and `dumpsys activity services` still reported
`isForeground=true foregroundId=42 types=0x00000010`. So the radio kept working with the app
in the background, which is the reason the foreground service exists.

**Stop check:** "Stop mesh" produced `service,stop` / `server,stopped` / `advertise,stopped`
with the node ID present on every row, and the notification disappeared.

## Things the emulator cannot tell us

- Real range, wall penetration, and how long a connection survives when a phone walks away.
- Real RSSI, real transmit power, real battery cost.
- Behaviour of vendor Bluetooth stacks (Samsung, Xiaomi, …), which is where most real
  two-phone bugs appear.

Those are the reasons the two-phone test still has to be run by a human.
