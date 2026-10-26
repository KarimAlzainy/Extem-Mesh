# Judge Q&A — what to say, in your own words

Rules of the game:

1. **Short answer first, then one supporting number.** Judges stop listening after ~20
   seconds if you ramble.
2. **Never invent a number.** If you don't remember, say "it's in the report, section 2".
3. **"I didn't measure that" is a strong answer** when followed by "here's how I'd
   measure it". It is only weak when you try to bluff.
4. If a question is outside your project, say so and bridge back: "That's about X, which
   I didn't test; what I did test is Y."

Numbers below are the ones in `results/REPORT.md`. If you regenerate the report, copy
the new values here.

---

## A. The problem

**Q: Why does this project matter?**
When a disaster destroys towers or the power grid, every normal messaging app stops
working at exactly the moment people need it. EXTEM-Mesh removes the requirement for
infrastructure: the phones themselves carry the messages.

**Q: Who would actually use this?**
Search-and-rescue teams in areas with no coverage, families in a blackout, people in a
crowded event where the network is overloaded. Not as a replacement for a phone
network — as a fallback when it's gone.

**Q: Isn't this just Bluetooth file sharing?**
No. File sharing needs both people present at the same moment and one of them to press
send. Here the message is stored and relayed by strangers' phones it has never met,
and it can cross several hops over minutes or hours.

## B. How it works

**Q: Explain your protocol in 30 seconds.**
Four steps: DISCOVER a phone in range, EXCHANGE what each side already has, STORE
anything new in an encrypted queue, RELAY it at the next encounter. Messages have a
30-minute lifetime and a limited number of copies, so they don't live forever.

**Q: How does a message know where to go?**
It has a destination ID and a time-to-live. Every phone that holds it tries to hand a
copy to phones it meets. Copies are limited (Spray-and-Wait: start with 8, halve at
each handover), so the message spreads like a rumour instead of flooding everything.

**Q: What happens if two people never meet?**
Then that path doesn't deliver. Our results measure exactly that: some messages are
physically unreachable within their lifetime, and we report that share separately
instead of hiding it.

**Q: Why not just use Wi-Fi Direct / LoRa / a satellite?**
Wi-Fi Direct has higher range but much higher power and pairing delay. LoRa needs extra
hardware nobody carries. Satellite needs a subscription and a clear sky. BLE is already
in every phone in the room, and its advertising cost is almost zero.

## C. The numbers and the method

**Q: Where does 78% come from?** *(the original poster's number)*
It was an assertion. In this version the number is measured: 84.5% delivery in the
sparse-district scenario over 12 independent seeds, with a 95% confidence interval of
about ±0.6 points. The raw rows are in `results/raw-runs.csv`.

**Q: You simulated it. That proves nothing about reality.**
It proves the protocol logic and the energy accounting are consistent, and it lets me
compare four designs under identical conditions — which you cannot do with three phones.
Real-device validation is the next step and I state that limitation explicitly.

**Q: Why 40 phones? Why 600 metres?**
It's a district-sized area with a realistic walking population, sparse enough that a
destination is typically 3-5 relays away. The scenario file is
`src/cli/experiments.js`; changing it takes one line and re-running takes five minutes.

**Q: Why 12 seeds?**
One run is an anecdote. Twelve independent runs give a confidence interval tight enough
to separate the designs; every difference I report is larger than its interval.

**Q: What is a p-value, in your own words?**
The chance of seeing a gap that large if the two designs were actually identical. Mine
are below 0.001, so the gap is not luck. I also report a bootstrap interval, which does
not assume the data is a bell curve.

**Q: What is the battery number based on?**
A current-draw model: time spent scanning × (CPU awake + radio), plus a small cost per
discovery and per transfer. Every constant is listed in the report and re-run at 0.5×
and 1.5× to show the conclusion doesn't depend on my guesses.

**Q: Why did you censor the last 30 minutes of each run?**
A message created one minute before the end cannot finish its 30-minute lifetime.
Counting it as a failure would punish slow designs for the shape of my experiment. I
exclude messages that had no full lifetime — and I say so in the report.

## D. The hard technical questions

**Q: Android limits background BLE scanning. How do you handle that?**
With a foreground service of type `connectedDevice` and a user-granted battery
optimisation exemption. Android 12+ also requires `BLUETOOTH_SCAN` with
`neverForLocation`, and Android 8+ throttles background scans to five starts per 30
seconds — which is another reason to scan in scheduled windows rather than continuously.

**Q: Android randomises MAC addresses. Can you even identify peers?**
Not by MAC. Peers are identified by an application-level node ID exchanged inside the
protocol, so MAC randomisation costs us nothing.

**Q: Why does iOS not work?**
iOS suspends BLE advertising when an app goes to the background, so an iPhone cannot
advertise itself as a relay. A cross-platform claim would be false. I scoped the project
to Android and said so.

**Q: How much data can one encounter move?**
At roughly 8 KB/s effective GATT throughput and 512-byte messages, about 13 messages
per second of contact, capped at 32 per rendezvous in the phone implementation. That is
why summary exchange and copy budgets matter.

**Q: What breaks if 200 people use it at once?**
Buffer pressure and collisions. Our flooding experiment already shows the failure mode:
at ~39 sends per delivery the queue overflows and delivery collapses to ~42%. The
protocol must stay spray-based, not flood-based.

**Q: Is your encryption real?**
The payload is AES-256-GCM with a key derived by HKDF; the phone code includes an ECDH
handshake. What it does *not* yet do is prove who sent a message — a malicious node
inside the mesh could forge a sender. That's Ed25519 signatures, and it's listed as
future work, not claimed as done.

**Q: What if someone injects fake messages to drain batteries?**
A real attack. With copy limits and TTLs the damage is bounded, but rate limiting and
signatures are the correct fix. I can describe the mitigation, but I did not test it.

## E. Novelty — the question that decides your score

**Q: Bridgefy / Briar / FireChat already do this. What's new?**
They all do store-and-forward over BLE. What I added is the power side: I measured what
naive duty cycling does to multi-hop delivery — it collapses to ~70% at 35% and ~42% at
25% — and showed why: two phones can be permanently out of phase. Then I applied a
coprime (quorum) schedule that guarantees any two phones overlap within 35 seconds at
the same 31% energy cost, which recovers delivery to 84.5%.

**Q: Is the quorum idea yours?**
No, and I don't claim it is. Neighbour-discovery quorum schedules are known in the
networking literature (Disco, U-Connect). My contribution is applying it to a
store-and-forward emergency messenger, and *measuring* the delivery-versus-battery
trade-off in that setting — including proving the naive alternative fails.

**Q: What would you do with another six months?**
Three things: real 3-phone trials to replace modelled constants, signature-based
identity so messages can't be forged, and a wake-up controller that learns meeting
patterns instead of using a fixed quorum.

## F. Ethics, safety, rules

**Q: Could this be misused?**
Any offline messenger can be. Its purpose is disaster response; I only test with
consenting classmates, and I don't deploy it publicly. I also don't intercept other
people's traffic — the app only handles messages it created or was given.

**Q: Did you need special forms for this?**
It's software with no human or animal subjects, so no human-subjects forms. I still
completed the SRC paperwork the fair requires: Form 1, research plan, 1A, 1B, C1 and
the mentor form. If I use external devices or lithium batteries for the hardware demo, I
add the risk-assessment form (Form 3).

**Q: Is all the code yours?**
The simulator, the protocol and the tests in this repository are mine. The Android
files are my implementation; I used standard Android APIs and documented algorithms
(Spray-and-Wait, quorum discovery, AES-GCM) and cite them.

## G. Questions designed to break you

**Q: Your simulation assumes 25 metres of range. Prove it.**
I can't from the simulation — it's an assumption. On real phones BLE in a corridor is
often 10-30 m. That's exactly why the next experiment is a phone-to-phone range test,
and why the report has a sensitivity table.

**Q: If I halve your battery current assumptions, does your conclusion survive?**
The absolute percentage changes (it roughly halves) but the *ratio* between the designs
barely moves: the saving versus always-listening stays between ~56% and ~65% across
±50% on every constant. Report §7.

**Q: Show me the raw data.**
`results/raw-runs.csv` — one row per run, with the seed. `npm run report` regenerates
every table from it in about five minutes.

**Q: What's the weakest part of your project?**
The gap between model and hardware. Everything else — the protocol, the statistics, the
reproducibility — is solid, but the constants are assumptions until I measure three real
phones. I would rather say that than overclaim.

**Q: What result would have proved you wrong?**
If the quorum schedule had produced the same delivery as the naive fixed schedule, my
central claim would be dead. It didn't: at the same energy, quorum is about +14.3
points over naive duty cycling, p < 0.001.

---

## H. Arabic quick answers (for an Arabic-speaking judge)

**ما مشكلة المشروع؟**
عند انقطاع شبكات المحمول في الكوارث، تتوقف كل تطبيقات المراسلة لأنها تحتاج برج شبكة
لحظة الإرسال. مشروعي يجعل الهواتف نفسها تنقل الرسائل وتخزنها وتسلّمها عند اللقاء، بدون
أي بنية تحتية.

**كيف تعمل الرسالة؟**
أربع خطوات: اكتشاف هاتف قريب، تبادل قائمة ما لدينا، تخزين ما هو جديد مشفّرًا، ثم تمريره
في اللقاء التالي. لكل رسالة مدة صلاحية وعدد نسخ محدود حتى لا تُغرق الشبكة.

**ما الجديد في مشروعك؟**
قياس أثر النوم على التسليم: النوم الدوري البسيط يخفض التسليم إلى حوالي ٧٠٪، لأن هاتفين قد
يكونان في طور متعاكس فلا يلتقيان أبدًا. استخدمت جدولًا بأرقام أولية متتالية (٥ و٧) يضمن
التقاء أي هاتفين خلال ٣٥ ثانية بنفس استهلاك الطاقة تقريبًا، فعاد التسليم إلى 84.5%.

**هل هذه أرقام حقيقية؟**
أرقام المحاكاة نعم، ولها ملف بيانات خام ويمكن إعادة تشغيلها بنفس النتائج. أما الهواتف
الحقيقية فلم تُقس بعد، وهذا مذكور صراحة كخطوة تالية.

**ما أكبر ضعف في المشروع؟**
الفجوة بين المحاكاة والهاتف الحقيقي: ثوابت استهلاك الطاقة افتراضات حتى أقيسها على ثلاثة
هواتف.
