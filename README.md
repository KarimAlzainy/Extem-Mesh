# EXTEM-Mesh

A low-power delay-tolerant BLE mesh for offline emergency messaging — plus the
simulation that measures whether it actually works.

> **Status**
> | Part | State |
> |---|---|
> | Protocol + simulator (`src/`) | **runnable and tested** — `npm test`, `npm run report` |
> | Measured results (`results/`) | **generated** from 226 seeded runs, reproducible |
> | Android prototype (`android/`) | **Stage 1 builds and runs** — APK built with AGP 9.4.1 / Gradle 9.6 / JDK 17. A full message exchange between two emulators over virtual Bluetooth is logged in `evidence/emulator/`. The real two-phone BLE radio link still has to be confirmed on physical phones |
> | Poster edits (`docs/POSTER_EDITS.md`) | ready to apply |

## Headline numbers (sparse district, 40 phones, 12 seeds each)

| Design | Delivery | Listening | Extra battery / 12 h |
|---|---|---|---|
| Epidemic flooding | 42.2% | 100% | 13.6% |
| Spray-and-Wait, always listening | 88.8% | 100% | 12.7% |
| Naive periodic sleep (35%) | 70.2% | 35% | 5.1% |
| **EXTEM quorum (5,7) — proposed** | **84.5%** | **31%** | **4.71%** |

The interesting row is the third: sleeping naively costs 14.3 points of delivery,
because two phones can be permanently out of phase and never hear each other. Coprime
scheduling gets the delivery back at the same energy cost. Full report:
[`results/REPORT.md`](results/REPORT.md).

## Quick start

```bash
npm test          # 16 unit tests (protocol, energy, quorum guarantee, statistics)
npm run demo      # a 1-minute story of one message, printed in the terminal
npm run report    # 226 runs (~5 min) -> results/REPORT.md + CSV + SVG charts
```

Node 20+ only. No dependencies: `npm install` is only needed for the lockfile.

## What it is

Four steps, repeated once per second on every phone:

1. **DISCOVER** a peer in BLE range.
2. **EXCHANGE** summary vectors — "what do you already have?".
3. **STORE** anything new in the local cache (encrypted from Stage 4).
4. **RELAY** copies onward at the next encounter, subject to a copy budget and a TTL.

The research question is not "does store-and-forward work" — it does, and Bridgefy,
Briar and Serval already ship it. The question is **what happens to multi-hop delivery
when you make the radio sleep to save battery**, and the answer is: it collapses,
because discovery is a rendezvous problem, not a probability problem.

## The three ideas

- **Spray-and-Wait** (L=8): messages spread like a rumour, not a flood. Flooding was
  measured and lost: 42.2% delivery at ~39 transmissions per delivered message,
  because buffers overflow.
- **Coprime scan schedule**: listen when the slot is a multiple of 5 or 7. Any two
  phones overlap within 35 slots regardless of phase; duty cycle ~31%.
- **TTL-aware hop accounting**: hop distance is computed as a temporal shortest path
  inside each message's lifetime, so "3–5 hops" means something.

## Repository map

```
src/core/       energy model, duty cycle, routing policies, seeded RNG
src/sim/        the protocol (engine.js) and temporal hop analysis (hops.js)
src/cli/        experiments, statistics, report generation, demo
tests/          16 tests, including the proof that fixed schedules can never meet
results/        REPORT.md, raw-runs.csv, charts/*.svg  (regenerate, don't hand-edit)
android/        Kotlin app, Stage 1: BLE link, permissions, foreground service  (see its README)
evidence/       two-emulator run: CSV logs + screenshots (virtual Bluetooth, not radio)
docs/           HOW_IT_WORKS.md, EXPLAINED.md, JUDGE_QA.md, POSTER_EDITS.md
```

## Honesty rules this repo follows

1. Every number in `results/REPORT.md` is computed from `results/raw-runs.csv`.
2. Hardware constants are assumptions and are re-run at 0.5× and 1.5× (report §7).
3. Messages that never had a full lifetime are excluded, and the exclusion is stated.
4. Messages whose destination is unreachable within the TTL are a separate bucket.
5. Nothing in `android/` is claimed as *radio* behaviour until it runs on physical phones. The Stage 1 app is built and its full exchange ran between two emulators over virtual Bluetooth — that is app logic, not radio.

## Limitations

- Radio range, connection stability and current draw are modelled, not measured.
- Android only: iOS suspends BLE advertising in the background.
- Payloads are **designed** to be encrypted with AES-GCM (Stage 4); the current Stage 1 app sends plaintext over a live link only, and sender identity is not signed.
- The Android app is Stage 1: it links two phones and shows messages, but does not store them for later yet (Stage 2) and does not duty-cycle the radio (Stage 3).
- The 40-phone district is a scenario, not a measurement of a specific place.

## Credits

Student: Karim Mohammed Alzainy — Mohammed Samir Idrees Distinguished Language School,
Tanta East, Gharbia. Mentor: Mr. Mohammed Mugahed.
Project registered for the Gharbia preliminary science and engineering fair 2026/2027
(field: Software / Systems, SOFT).
