import { test } from 'node:test';
import assert from 'node:assert/strict';

import { mulberry32 } from '../src/core/rng.js';
import { radioEnergy, makeDutyCycle } from '../src/core/model.js';
import { copiesToGive, initialCopies } from '../src/core/policies.js';
import { isActive, quorumDuty, defaultConfig, runTrial } from '../src/sim/engine.js';
import { computeMessageHops, annotateMessages } from '../src/sim/hops.js';
import { mean, sd, ci95, compare } from '../src/cli/stats.js';

test('the random generator is reproducible', () => {
  const a = mulberry32(123);
  const b = mulberry32(123);
  const xs = Array.from({ length: 20 }, () => a());
  const ys = Array.from({ length: 20 }, () => b());
  assert.deepEqual(xs, ys);
  assert.notDeepEqual(xs, Array.from({ length: 20 }, () => mulberry32(124)()));
});

test('energy: listening every second costs more than listening 20% of the time', () => {
  const always = radioEnergy({ duty: 1, hours: 12 });
  const rarely = radioEnergy({ duty: 0.2, hours: 12 });
  assert.ok(always.extraMah > rarely.extraMah);
  assert.ok(rarely.extraPercent > 0);
});

test('energy: with scanning off, only advertising is paid', () => {
  const off = radioEnergy({ duty: 0, hours: 12 });
  // advertising stays on so peers can still discover the node: 0.8 mA for 12 h
  assert.ok(Math.abs(off.extraMah - 9.6) < 1e-6);
  assert.ok(off.extraPercent < 0.3);
});

test('energy scales with the battery size', () => {
  const small = radioEnergy({ duty: 0.3, hours: 12, batteryMah: 2000 });
  const big = radioEnergy({ duty: 0.3, hours: 12, batteryMah: 4000 });
  assert.ok(small.extraPercent > big.extraPercent);
});

test('duty cycle helper never returns a zero-length window', () => {
  for (const d of [0.01, 0.05, 0.2, 0.35, 0.99, 1]) {
    const dc = makeDutyCycle(d, 20, 7);
    assert.ok(dc.window >= 1);
    assert.ok(dc.window <= dc.period);
  }
});

test('spray-and-wait halves its copy budget and stops at one copy', () => {
  const cfg = defaultConfig({ sprayL: 8 });
  assert.equal(initialCopies('spray', cfg), 8);
  assert.equal(copiesToGive('spray', { msg: { dst: 9 }, copies: 8 }, 3), 4);
  assert.equal(copiesToGive('spray', { msg: { dst: 9 }, copies: 4 }, 3), 2);
  assert.equal(copiesToGive('spray', { msg: { dst: 9 }, copies: 1 }, 3), 0, 'last copy is not given away');
  assert.equal(copiesToGive('spray', { msg: { dst: 3 }, copies: 1 }, 3), 1, 'but it is delivered to the destination');
});

test('epidemic ignores copy limits', () => {
  assert.equal(copiesToGive('epidemic', { msg: { dst: 9 }, copies: 1 }, 3), Infinity);
});

test('quorum duty matches the formula', () => {
  assert.ok(Math.abs(quorumDuty(5, 7) - (1 / 5 + 1 / 7 - 1 / 35)) < 1e-12);
  const d = quorumDuty(5, 7);
  assert.ok(d > 0.3 && d < 0.32, `expected ~31%, got ${d}`);
});

test('quorum guarantee: any two phases overlap inside one period', () => {
  const cfg = defaultConfig({ dutyMode: 'quorum', q1: 5, q2: 7 });
  const period = cfg.q1 * cfg.q2;
  for (let a1 = 0; a1 < period; a1++) {
    for (let a2 = 0; a2 < period; a2++) {
      const n1 = { dutyOffset: a1 };
      const n2 = { dutyOffset: a2 };
      let overlap = false;
      for (let t = 0; t < period; t++) {
        if (isActive(n1, t, cfg) && isActive(n2, t, cfg)) {
          overlap = true;
          break;
        }
      }
      assert.ok(overlap, `phases ${a1} and ${a2} never overlap`);
    }
  }
});

test('fixed duty cycles can be phase-incompatible (why quorum exists)', () => {
  const cfg = defaultConfig({ dutyMode: 'fixed', duty: 0.25, dutyPeriod: 20 });
  const n1 = { duty: makeDutyCycle(0.25, 20, 0), dutyOffset: 0 };
  const n2 = { duty: makeDutyCycle(0.25, 20, 0), dutyOffset: 10 };
  let overlap = false;
  for (let t = 0; t < 20; t++) {
    if (isActive(n1, t, cfg) && isActive(n2, t, cfg)) overlap = true;
  }
  assert.equal(overlap, false, 'a 5/20 window with a 10 s offset never aligns');
});

test('hop distance is measured within the message lifetime', () => {
  // A - B contact at 0..10 s, B - C contact at 20..30 s
  const intervals = [
    { a: 0, b: 1, s: 0, e: 10 },
    { a: 1, b: 2, s: 20, e: 30 },
  ];
  const msgs = [
    { id: 'long', src: 0, dst: 2, createdAt: 0, ttl: 60 },
    { id: 'short', src: 0, dst: 2, createdAt: 0, ttl: 15 },
  ];
  const map = computeMessageHops(intervals, 3, msgs);
  assert.equal(map.get('long').hops, 2);
  assert.equal(map.get('long').reachable, true);
  assert.equal(map.get('short').reachable, false, 'B is only reachable after the short TTL expired');

  const annotated = annotateMessages(msgs, map);
  assert.equal(annotated[0].hopBucket, '2');
  assert.equal(annotated[1].hopBucket, 'unreachable');
});

test('a direct contact is one hop', () => {
  const intervals = [{ a: 4, b: 7, s: 5, e: 9 }];
  const map = computeMessageHops(intervals, 10, [{ id: 'm', src: 4, dst: 7, createdAt: 0, ttl: 100 }]);
  assert.equal(map.get('m').hops, 1);
});

test('the simulator is deterministic for a given seed', () => {
  const cfg = { nodes: 12, area: 200, duration: 300, seed: 5, policy: 'spray', dutyMode: 'fixed', duty: 1 };
  const a = runTrial(cfg);
  const b = runTrial(cfg);
  assert.equal(a.totals.pdr, b.totals.pdr);
  assert.equal(a.totals.transmissions, b.totals.transmissions);
});

test('a different seed produces a different run', () => {
  const base = { nodes: 12, area: 200, duration: 300, policy: 'spray', dutyMode: 'fixed', duty: 1 };
  const a = runTrial({ ...base, seed: 1 });
  const b = runTrial({ ...base, seed: 2 });
  assert.notEqual(a.totals.transmissions, b.totals.transmissions);
});

test('sending messages works at all: some traffic is delivered', () => {
  const r = runTrial({ nodes: 20, area: 150, duration: 600, policy: 'spray', dutyMode: 'fixed', duty: 1, seed: 3 });
  assert.ok(r.totals.delivered > 0);
});

test('statistics helpers behave', () => {
  assert.equal(mean([1, 2, 3]), 2);
  assert.ok(Math.abs(sd([2, 4, 4, 4, 5, 5, 7, 9]) - 2.13809) < 1e-4);
  const c = ci95([10, 12, 11, 13, 9]);
  assert.ok(c.lo < c.mean && c.hi > c.mean);
  const cmp = compare([1, 2, 3, 4, 5], [1, 2, 3, 4, 5], { resamples: 500 });
  assert.ok(Math.abs(cmp.diff) < 1e-9);
  assert.ok(cmp.p > 0.5, 'identical samples should not be significant');
});
