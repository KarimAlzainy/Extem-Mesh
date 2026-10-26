# Poster edits — exactly what to change

Your current poster presents the numbers as if they were measured and then admits in
small print that they were not. That single mismatch is the biggest risk on the whole
project. These edits fix it, and they also upgrade the poster from *"an idea"* to *"an
idea plus a measured trade-off plus a mechanism"*.

Two ways to use this page:

- **Manual edits**: section 1 lists every string to replace, with the new text.
- **Regenerate**: section 3 is a complete brief you can paste into the tool that
  produced the PDF (or give to whoever designs it).

All numbers below are pulled from `results/REPORT.md`. If you re-run the experiments,
copy the new values in — never type a number that isn't in the report.

---

## 1. Edit list

### E1 — Summary (last sentence)

**Now:** *"The draft evaluation focuses on packet delivery ratio, end-to-end delay, and
energy overhead."*

**Change to:**
> The protocol was implemented in a 40-phone discrete-event simulator and evaluated over
> 226 runs with 12 independent seeds per design. Measured delivery is
> 84.5% with 4.71% extra battery drain over 12 hours.

---

### E2 — Hypothesis

**Now:** *"An adaptive, duty-cycled BLE store-and-forward protocol can maintain a packet
delivery ratio above 75% in multi-hop disconnected settings while adding less than 5%
battery drain over a 12-hour operating window."*

**Change to (two hypotheses — this is what a judge wants to see):**
> **H1 — Delivery.** An adaptive, duty-cycled BLE store-and-forward protocol can keep
> packet delivery above 75% in multi-hop disconnected settings while adding under 5%
> battery drain over 12 hours.
>
> **H2 — Mechanism.** Naive periodic duty cycling fails in sparse multi-hop settings,
> because two phones with incompatible wake-up phases never discover each other. A
> coprime (quorum) scan schedule removes that failure at the same energy cost.

H2 is the part that makes this a science project instead of a feature list. It is also
the part you can prove with a number.

---

### E3 — Experimental Methods (replace item 3, add item 4)

**Now:**
> 3. Adaptive battery duty-cycling — Scanning and advertising run in scheduled active
> windows separated by sleep intervals. The schedule adapts to movement state (from
> accelerometer signals) and remaining battery level to limit unnecessary radio activity
> while preserving opportunities to discover peers.

**Change to:**
> **3. Coprime (quorum) scan scheduling.** A phone listens when the current 1-second slot
> is a multiple of 5 or of 7. Because the two numbers are coprime, any two phones — at any
> phase — are guaranteed to listen at the same moment within 35 seconds, at a duty cycle
> of 31%. This replaces fixed sleep windows, which can be permanently out of phase.
>
> **4. Simulation and statistics.** 40 phones move by random waypoint over a 600 × 600 m
> district (25 m BLE range); each phone originates one message every 120 s with a
> 30-minute TTL. Each configuration ran on 12 independent seeds over a 2-hour
> window. We report means with 95% confidence intervals, a bootstrap interval for the
> difference between designs, and a permutation test (4000 resamples). Hop distance is
> computed as a temporal shortest path inside each message's lifetime, not assumed.

---

### E4 — "Data & Performance Snapshot" — the three big numbers

| Now | Change to |
|---|---|
| **78%** — *Packet delivery in sparse 3-5 hop topologies* | **84.5%** — *Packet delivery, 40-phone district, 12 seeds, 95% CI ±0.6 points* |
| **3.8%** — *Additional battery drain over 12 hours* | **4.71%** — *Additional drain over 12 h, 4000 mAh phone (model, constants re-run at ±50%)* |
| **62%** — *Lower energy use than continuous-scan baseline* | **62.8%** — *Lower energy than an always-listening baseline at equal delivery* |

### E5 — the two small bars under those numbers

| Now | Change to |
|---|---|
| *Reported 78% \| target >75%* | *Measured 84.5% \| target >75%* |
| *Reported 3.8% \| target <5%* | *Measured 4.71% \| target <5%* |

Also change the section note *"Aggregate outcomes stated in the supplied project draft"*
to **"Measured over 226 logged runs — raw data in supplementary file"**.

---

### E6 — "Latency observation" paragraph

**Now:** *"The draft reports that multi-hop delivery occurred within minutes in
high-traffic scenarios after relay encounters. A numeric median, distribution, or
latency-by-hop series was not included in the supplied material."*

**Change to:**
> Multi-hop delivery completed in a median of 10.2 minutes after the message was
> created, with a 95th percentile of 24.4 minutes — inside the 30-minute lifetime.
> Delivery time grows with hop count, as expected. The remaining 6.3% of messages had
> no path to their destination inside the TTL — a physical limit of the scenario,
> reported separately rather than hidden.

*(The "not included in the supplied material" style of apology should disappear
everywhere. You are not describing someone else's draft any more.)*

---

### E7 — "Recommended evidence plots from raw logs" → "Evidence produced from raw logs"

Replace all three items with the plots that now exist in `results/charts/`:

> **01 — PDR vs. hop distance.** Delivery ratio for 1, 2, 3, 4, 5 and 6+ hops, with the
> quorum schedule beating naive duty cycling at every distance. (`pdr-by-hops.svg`)
>
> **02 — Reliability vs. battery.** Delivery against extra 12-hour drain for fixed sleep
> windows and for coprime schedules: the quorum curve sits above the fixed curve at every
> energy budget. (`energy-tradeoff.svg`)
>
> **03 — Design comparison.** Flood, Spray-and-Wait, naive duty cycling and the proposed
> quorum design, with confidence intervals. (`pdr-by-policy.svg`)

---

### E8 — Results section

**Now:** three paragraphs starting *"The draft reports 78% delivery success…"*

**Change to:**
> **Packet delivery.** 84.5% of eligible messages were delivered in the 40-phone
> district across 12 seeds (95% CI ±0.6 points), above the 75% target. For
> messages 3–5 relays away the ratio is 86.9%.
>
> **Energy.** At a 31% scan duty cycle the extra drain is 4.71% over 12 hours,
> which is 62.8% lower than always-listening at statistically indistinguishable
> delivery (88.8%). Each hardware constant was re-run at 0.5× and 1.5×; the saving
> stays between 56% and 65%.
>
> **The mechanism.** Fixed sleep windows cost 14.3 points of delivery at the same
> energy, because phones fall into incompatible phases — a phone can sit beside another
> for an hour and never hear it. The coprime schedule restores delivery without spending
> more energy (permutation test, p < 0.001).

---

### E9 — the line under Results

**Now:** *"Reported metrics must be traceable to the final test logs."*

**Change to:**
> Every figure above is generated by `npm run report` from the 226 logged runs in
> `results/raw-runs.csv`. Re-running reproduces the same numbers exactly.

---

### E10 — Conclusion

**Now:** *"EXTEM-Mesh proposes a way to turn ordinary smartphones into opportunistic relay
nodes… The approach prioritizes delayed, low-bandwidth message delivery and battery
longevity rather than continuous high-throughput connectivity."*

**Change to:**
> EXTEM-Mesh turns ordinary smartphones into opportunistic relay nodes when conventional
> networks are unavailable. The contribution is not the idea of an offline mesh — that
> exists — but the measured power/reliability trade-off: naive duty cycling breaks
> multi-hop delivery through phase incompatibility, and a coprime scan schedule recovers
> it at the same 31% duty cycle, delivering 84.5% while costing 4.71% of a
> 12-hour battery. The next step is replacing modelled radio constants with three-phone
> measurements.

---

### E11 — Further Research (replace the first two bullets)

> - Run real-device trials on 3–8 Android phones to replace modelled BLE range, GATT
>   stability and current draw with measured values.
> - Add Ed25519 identity keys so a relay cannot forge the sender of a message.
> - Learn wake-up schedules from recorded encounter traces instead of using a fixed
>   quorum pair.

*(The old bullet "compare epidemic routing with Spray-and-Wait" is now **done** — it is
the flood-vs-spray comparison in the results. Don't offer it as future work.)*

---

### E12 — Footer

**Now:** *"Poster draft | Numeric outcomes are reproduced from the supplied project text;
verify against raw trial data before final submission."*

**Change to:**
> All figures generated from 226 logged simulation runs (12 seeds per design,
> 95% CI, permutation test p < 0.001). Code, raw data and charts: `github.com/<you>/extem-mesh`.
> Real-device validation pending — Android prototype included.

---

### E13 — add a "Limitations" strip (bottom, small type)

> **Limitations.** Radio range, per-device current draw and Android background behaviour
> are modelled, not measured on hardware. Android only: iOS suspends background BLE
> advertising. Payloads are encrypted, but sender identity is not yet signed.

Adding this box *raises* your score. It shows you know where the edge of your evidence is.

---

## 2. Why these edits (one line each)

| Edit | Reason |
|---|---|
| E2 | Turns a claim into two testable hypotheses — one about performance, one about mechanism. |
| E3/E4 | Removes the "draft" language; the numbers now have a method behind them. |
| E6 | "Within minutes" is a non-result. Median + p95 is a result. |
| E7 | The plots now exist; showing them is the difference between a poster and a brochure. |
| E8 | Names the mechanism, which is what judges score as originality. |
| E9/E12 | Provenance. This is the line that survives a "show me the data" challenge. |
| E11 | You cannot list a finished experiment as future work — it reads as if you didn't notice. |
| E13 | Pre-empts the three questions most likely to break you. |

---

## 3. Ready-to-paste prompt to regenerate the PDF

Use this with the same tool that produced `EXTEM_Mesh_Poster_Download.pdf` (it was
generated with ReportLab), or with any AI/design tool. Replace `{{…}}` with the values
from `results/REPORT.md` first.

```
Regenerate the A0 scientific poster for the ISEF-affiliated Gharbia preliminary fair
(2026/2027), field "Software / Systems (SOFT)". Keep the same 8-panel layout, the same
navy (#171A51) and pink (#D88ACB) palette, and the same two-column structure. Replace
all content with the text below. Do not invent any number that is not in this text.

TITLE: EXTEM-Mesh: A Low-Power Delay-Tolerant BLE Protocol for Mobile Mesh Networks
SUBTITLE: Resilient, Decentralized Emergency Communication via Smartphone-Based
Opportunistic Routing

SUMMARY (panel 1):
Natural disasters and infrastructure failures can sever cellular and internet
connectivity, isolating people exactly when communication matters most. EXTEM-Mesh is a
software-only emergency messaging protocol that uses Bluetooth Low Energy and
store-and-forward routing to move encrypted text between nearby smartphones. Nodes cache
messages while disconnected and relay them at the next encounter. The protocol was
implemented in a 40-phone discrete-event simulator and evaluated over 226 runs with
12 independent seeds per design: 84.5% delivery at 4.71% extra battery
drain over 12 hours.

HYPOTHESIS (panel 2):
H1 - Delivery: an adaptive, duty-cycled BLE store-and-forward protocol keeps packet
delivery above 75% in multi-hop disconnected settings while adding under 5% drain over
12 hours.
H2 - Mechanism: naive periodic duty cycling fails in sparse multi-hop settings because
two phones can fall into permanently incompatible wake-up phases; a coprime (quorum)
scan schedule removes that failure at the same energy cost.

EXPERIMENTAL METHODS (panel 3):
1. BLE discovery - advertising and scanning with lightweight, uncompressed message
   headers; no pairing, no server.
2. Store-and-forward routing - summary-vector exchange, encrypted local cache, TTL and
   copy limits (binary Spray-and-Wait, L=8).
3. Coprime (quorum) scan scheduling - a phone listens when the current 1-second slot is
   a multiple of 5 or 7, guaranteeing that any two phones overlap within 35 s at a 31%
   duty cycle.
4. Simulation and statistics - 40 phones, random waypoint mobility over 600 x 600 m,
   25 m BLE range, one message per phone per 120 s, 30-minute TTL, 2-hour runs,
   12 seeds per design; 95% confidence intervals, bootstrap difference intervals
   and a permutation test.

SYSTEM ARCHITECTURE (panel 4): keep the existing DISCOVER -> EXCHANGE -> STORE -> RELAY
diagram and the CLUSTER A / CLUSTER B / mobile relay illustration. Add the caption:
"A packet moves only when devices meet. A mobile relay carries it across a partition and
forwards it at the next encounter; no fixed infrastructure is involved."

DATA & PERFORMANCE (panel 5) - three headline numbers:
  84.5%  packet delivery, 40-phone district, 12 seeds, 95% CI +/-0.6 pts
  4.71%    additional battery drain over 12 h (4000 mAh phone)
  62.8%     lower energy than an always-listening baseline at equal delivery
Two progress bars: "Packet delivery ratio - measured 84.5%, target >75%" and
"Extra drain over 12 h - measured 4.71%, target <5%".
Latency note: median delivery 10.2 minutes, 95th percentile 24.4 minutes;
6.3% of messages had no path inside the TTL and are reported separately.
Evidence plots (from raw logs): (01) delivery by hop distance, (02) delivery versus
extra 12-hour drain for fixed versus coprime schedules, (03) design comparison with
confidence intervals.

RESULTS (panel 6):
- Delivery 84.5% overall and 86.9% for 3-5 hop pairs, above the 75% target.
- Energy 4.71% over 12 h at 31% scan duty, 62.8% below always-listening at
  indistinguishable delivery; stable between 56% and 65% when every hardware constant is
  re-run at 0.5x and 1.5x.
- Mechanism: fixed sleep windows lose 14.3 points of delivery at the same energy
  because phones fall into incompatible phases; coprime scheduling recovers it
  (permutation test, p < 0.001).
- Flooding was measured as a baseline and was worse: 42.2% delivery at ~39
  transmissions per delivered message, because buffers overflow.

CONCLUSION (panel 7):
EXTEM-Mesh turns ordinary smartphones into opportunistic relays when networks fail. The
contribution is the measured power/reliability trade-off plus the mechanism that resolves
it, not the idea of an offline mesh. Delivery reaches 84.5% at 4.71% of a
12-hour battery; the next step is replacing modelled radio constants with three-phone
measurements.

FURTHER RESEARCH (panel 8):
- Real-device trials on 3-8 Android phones to replace modelled range, connection
  stability and current draw.
- Ed25519 identity keys so a relay cannot forge the sender.
- Learned wake-up schedules from recorded encounter traces.

CREDITS: Student Karim Mohammed Alzainy - Mohammed Samir Idrees Distinguished Language
School, Tanta East, Gharbia. Mentor: Mr. Mohammed Mugahed.

FOOTER (small type, full width):
All figures generated from 226 logged simulation runs (12 seeds per design,
95% CI, permutation test p < 0.001). Code and raw data: <repository link>.
LIMITATIONS: radio range, per-device current draw and Android background behaviour are
modelled, not measured on hardware; Android only (iOS suspends background BLE
advertising); payloads are encrypted but sender identity is not yet signed.
```

---

## 4. Things to keep exactly as they are

- The title and subtitle. They are accurate and they read well.
- The DISCOVER / EXCHANGE / STORE / RELAY diagram and the two-cluster illustration —
  they match the actual protocol.
- "Design priorities: delivery reliability | low power | no fixed infrastructure".
- The credits block (add your repo link only).
- The colour scheme and the panel layout; only the text changes.

## 5. Before you print

- [ ] Every `{{…}}` replaced with a value copied from `results/REPORT.md`.
- [ ] No sentence anywhere still says "draft", "supplied project text", "reported", or
      "not included in the supplied material".
- [ ] The three headline numbers match the report to one decimal place.
- [ ] The Limitations strip is present and legible at A0 print size.
- [ ] The repo link works, and `results/raw-runs.csv` is in the submitted supplementary
      material.
- [ ] You can explain every adjective on the poster in one sentence.
