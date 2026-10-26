// A 60-second story you can show on a projector (or read yourself before a demo).
// It uses the SAME functions as the simulator, so nothing here is a cartoon.

import { copiesToGive } from '../core/policies.js';
import { makeDutyCycle, radioEnergy } from '../core/model.js';
import { isActive, quorumDuty, defaultConfig } from '../sim/engine.js';

const line = (s = '') => console.log(s);
const rule = (t) => console.log(`\n${t}\n${'-'.repeat(t.length)}`);

line('');
line('EXTEM-Mesh — the life of one message');
line('====================================');

rule('1. A creates a message for C');
let copies = 8;
line('Phone A: "I need to reach C, but there is no network."');
line(`A stores the message locally and gives it ${copies} copies to spend.`);
line('Nothing is transmitted yet. A is not even listening every second.');

rule('2. A meets B in the corridor (t = 0 s)');
const give = copiesToGive('spray', { msg: { dst: 'C' }, copies }, 'B');
line(`A checks its copy budget: ${copies} copies.`);
line(`Binary Spray-and-Wait: keep ${copies - give}, hand ${give} to B.`);
copies -= give;
line(`A now holds 4 copies, B holds 4. The message has travelled one hop.`);

rule('3. B meets D across the partition (t = 240 s)');
const give2 = copiesToGive('spray', { msg: { dst: 'C' }, copies: 4 }, 'D');
line(`B keeps ${4 - give2} and gives ${give2} to D.`);
line('Now two carriers hold it. This is why it survives if one of them goes home.');

rule('4. B meets C (t = 610 s)');
const last = copiesToGive('spray', { msg: { dst: 'C' }, copies: 2 }, 'C');
line(`B has 2 copies left, but the peer IS the destination: give ${last}.`);
line('C receives it, decrypts it, and shows it. Delivery time = 610 s → 3 hops.');
line('');
line('What if nobody had met C? The message would expire after 30 minutes and');
line('be deleted. That is deliberate: a stale emergency message is worse than none.');

rule('5. Why the phone was asleep, and why that is hard');
const cfg = defaultConfig({ dutyMode: 'fixed', duty: 0.25, dutyPeriod: 20 });
const A = { duty: makeDutyCycle(0.25, 20, 0), dutyOffset: 0 };
const B = { duty: makeDutyCycle(0.25, 20, 0), dutyOffset: 10 };

const active = (n) => Array.from({ length: 40 }, (_, t) => (isActive(n, t, cfg) ? t : null)).filter((v) => v !== null);
line('Fixed schedule: 5 seconds listening every 20 seconds (25% duty).');
line(`A listens at: ${active(A).slice(0, 8).join(', ')} ...`);
line(`B listens at: ${active(B).slice(0, 8).join(', ')} ...`);
line('These two lists never share a number. A and B can stand together for an');
line('hour and hear nothing. That is not bad luck, it is arithmetic.');

rule('6. The fix: listen on multiples of two coprime numbers');
const qCfg = defaultConfig({ dutyMode: 'quorum', q1: 5, q2: 7 });
const QA = { dutyOffset: 0 };
const QB = { dutyOffset: 3 };
const qa = Array.from({ length: 36 }, (_, t) => (isActive(QA, t, qCfg) ? t : null)).filter((v) => v !== null);
const qb = Array.from({ length: 36 }, (_, t) => (isActive(QB, t, qCfg) ? t : null)).filter((v) => v !== null);
const meet = qa.filter((t) => qb.includes(t));
line(`A listens at: ${qa.slice(0, 10).join(', ')} ...`);
line(`B listens at: ${qb.slice(0, 10).join(', ')} ...`);
line(`First shared second: t = ${meet[0]} s. Guaranteed within 5 x 7 = 35 s, for any phase.`);
line(`Duty cycle: ${(100 * quorumDuty(5, 7)).toFixed(1)}% of the time.`);

rule('7. What that costs in battery (12-hour window, 4000 mAh phone)');
const quorum = radioEnergy({ duty: quorumDuty(5, 7), hours: 12, transfersPerHour: 8000, discoveriesPerHour: 3000 });
const continuous = radioEnergy({ duty: 1, hours: 12, transfersPerHour: 8000, discoveriesPerHour: 3000 });
line(`quorum schedule : +${quorum.extraMah.toFixed(0)} mAh  (+${quorum.extraPercent.toFixed(2)}%)`);
line(`always listening: +${continuous.extraMah.toFixed(0)} mAh  (+${continuous.extraPercent.toFixed(2)}%)`);
line(`saving: ${(100 * (1 - quorum.extraMah / continuous.extraMah)).toFixed(0)}%`);
line('');
line('Sanity check you can do on paper: mAh = mA x hours.');
line('Listening 31% of 12 h at ~50 mA is about 186 mAh. That is the whole idea.');

rule('8. Where the real numbers come from');
line('This story is one message. The project ran 226 simulated networks of 40 phones,');
line('12 independent seeds per design, and every figure is in results/REPORT.md.');
line('Run:   npm run report');
line('');
