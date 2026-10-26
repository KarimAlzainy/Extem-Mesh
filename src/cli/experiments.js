// The experiment plan. Everything the poster claims is measured here.
//
// Ablation logic (why each baseline exists):
//   1 Epidemic flood            -> upper bound on delivery, terrible overhead
//   2 Spray-and-Wait L=8        -> strong published baseline, continuous scan
//   3 EXTEM routing, continuous -> isolates the ROUTING change from the POWER change
//   4 EXTEM + naive 35% duty    -> shows what naive duty cycling costs
//   5 EXTEM + quorum scan (5,7) -> the proposed system
//
// 2 vs 3 tells us whether urgency ordering matters.
// 3 vs 4 tells us the price of sleeping.
// 4 vs 5 tells us whether the quorum schedule recovers that price.

import { runTrial } from '../sim/engine.js';
import { computeMessageHops, annotateMessages, HOP_BUCKETS } from '../sim/hops.js';
import { radioEnergy, ENERGY } from '../core/model.js';

// The contact trace depends only on mobility and the message-creation schedule, so
// for a given scenario + seed every policy sees the same trace. We therefore compute
// the (expensive) TTL-aware hop distances once per scenario+seed and reuse them.
const hopCache = new Map();
function hopMapFor({ scenarioKey, seed, run }) {
  const key = `${scenarioKey}|${seed}`;
  const cached = hopCache.get(key);
  if (cached && cached.size === run.messages.length) {
    const sample = run.messages[Math.floor(run.messages.length / 2)];
    if (cached.has(sample.id)) return cached;
  }
  const map = computeMessageHops(run.intervals, run.nodes, run.messages);
  hopCache.set(key, map);
  return map;
}

export const SEEDS_MAIN = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
export const SEEDS_SWEEP = [1, 2, 3, 4, 5, 6];
export const SEEDS_FAST = [1, 2, 3, 4, 5, 6, 7, 8];

export const POLICIES = [
  {
    key: 'epidemic',
    label: 'Epidemic flood',
    short: 'Epidemic',
    cfg: { policy: 'epidemic', dutyMode: 'fixed', duty: 1 },
  },
  {
    key: 'spray',
    label: 'Spray-and-Wait (L=8)',
    short: 'Spray L8',
    cfg: { policy: 'spray', dutyMode: 'fixed', duty: 1, sprayL: 8 },
  },
  {
    key: 'extem-cont',
    label: 'EXTEM routing, always listening',
    short: 'EXTEM cont.',
    cfg: { policy: 'extem', dutyMode: 'fixed', duty: 1, extemL: 8 },
  },
  {
    key: 'extem-fixed35',
    label: 'EXTEM + naive 35% duty cycle',
    short: 'EXTEM 35%',
    cfg: { policy: 'extem', dutyMode: 'fixed', duty: 0.35, extemL: 8 },
  },
  {
    key: 'extem-quorum',
    label: 'EXTEM + quorum scan (5,7)',
    short: 'EXTEM quorum',
    cfg: { policy: 'extem', dutyMode: 'quorum', q1: 5, q2: 7, extemL: 8 },
  },
];

export const SCENARIOS = {
  district: {
    label: 'Sparse district (40 phones, 600x600 m)',
    cfg: { scenario: 'uniform', nodes: 40, area: 600, range: 25, duration: 7200, createInterval: 120, ttl: 1800 },
  },
  partition: {
    label: 'Two separated clusters + mobile relays (ISEF poster case)',
    cfg: { scenario: 'clusters', nodes: 40, area: 900, range: 25, duration: 7200, createInterval: 120, ttl: 1800 },
  },
  fast: {
    label: 'Fast movement (vehicles / hurried crowd)',
    cfg: { scenario: 'fast', nodes: 40, area: 600, range: 25, duration: 7200, createInterval: 120, ttl: 1800 },
  },
};

export const SWEEP_FIXED = [1, 0.75, 0.5, 0.35, 0.25, 0.15];
export const SWEEP_QUORUM = [
  [3, 4],
  [4, 5],
  [5, 7],
  [7, 11],
  [9, 13],
];

function energyFor(totals) {
  const e = radioEnergy({
    duty: totals.avgDuty,
    hours: 12,
    transfersPerHour: totals.transfersPerHour,
    discoveriesPerHour: totals.discoveriesPerHour,
  });
  return {
    avgDuty: totals.avgDuty,
    extraMah: e.extraMah,
    extraPercent: e.extraPercent,
    totalPercent: e.totalPercent,
    txMah: e.txMah,
    cpuMah: e.cpuMah,
  };
}

function bucketStats(messages) {
  const out = {};
  for (const b of HOP_BUCKETS) out[b] = { n: 0, d: 0 };
  for (const m of messages) {
    if (!out[m.hopBucket]) out[m.hopBucket] = { n: 0, d: 0 };
    out[m.hopBucket].n++;
    if (m.delivered) out[m.hopBucket].d++;
  }
  return out;
}

/** Run one configuration with one seed and return every raw metric. */
export function runOne({ scenarioKey, policyKey, cfg, seed, log }) {
  const t0 = Date.now();
  const run = runTrial({ ...cfg, seed });
  const hopMap = hopMapFor({ scenarioKey, seed, run });
  const annotated = annotateMessages(run.messages, hopMap);
  const eligible = annotated.filter((m) => m.eligible);

  const energy = energyFor(run.totals);
  const buckets = bucketStats(eligible);
  const reachable = eligible.filter((m) => m.reachable);
  const deliveredReachable = reachable.filter((m) => m.delivered).length;

  const record = {
    scenario: scenarioKey,
    policy: policyKey,
    seed,
    nodes: run.nodes,
    created: run.totals.created,
    eligible: run.totals.eligible,
    delivered: run.totals.delivered,
    pdr: run.totals.pdr,
    reachableShare: eligible.length ? reachable.length / eligible.length : 0,
    pdrReachable: reachable.length ? deliveredReachable / reachable.length : 0,
    latencyMedian: run.totals.latencyMedian,
    latencyP95: run.totals.latencyP95,
    overheadRatio: run.totals.overheadRatio,
    avgDuty: run.totals.avgDuty,
    extraPercent: energy.extraPercent,
    transfersPerHour: run.totals.transfersPerHour,
    discoveriesPerHour: run.totals.discoveriesPerHour,
    drops: run.totals.drops,
    expired: run.totals.expired,
    avgBufferPeak: run.totals.avgBufferPeak,
    buckets,
    ms: Date.now() - t0,
  };
  if (log) {
    log(
      `  ${scenarioKey.padEnd(10)} ${policyKey.padEnd(14)} seed=${String(seed).padStart(2)} ` +
        `PDR=${(100 * record.pdr).toFixed(1)}%  duty=${(100 * record.avgDuty).toFixed(0)}%  ` +
        `extra=${record.extraPercent.toFixed(1)}%  (${record.ms} ms)`
    );
  }
  return record;
}

export function runMain(log) {
  const rows = [];
  for (const [scenarioKey, scenario] of Object.entries(SCENARIOS)) {
    if (scenarioKey === 'fast') continue;
    for (const policy of POLICIES) {
      for (const seed of SEEDS_MAIN) {
        rows.push(
          runOne({
            scenarioKey,
            policyKey: policy.key,
            cfg: { ...scenario.cfg, ...policy.cfg },
            seed,
            log,
          })
        );
      }
    }
  }
  return rows;
}

export function runSweep(log) {
  const rows = [];
  const base = { ...SCENARIOS.district.cfg, policy: 'extem', extemL: 8 };
  for (const duty of SWEEP_FIXED) {
    for (const seed of SEEDS_SWEEP) {
      const r = runOne({
        scenarioKey: 'district',
        policyKey: `fixed-${Math.round(duty * 100)}`,
        cfg: { ...base, dutyMode: 'fixed', duty },
        seed,
        log,
      });
      rows.push({ mode: 'fixed', dutyLabel: `${Math.round(duty * 100)}%`, duty, ...r });
    }
  }
  for (const [q1, q2] of SWEEP_QUORUM) {
    for (const seed of SEEDS_SWEEP) {
      const r = runOne({
        scenarioKey: 'district',
        policyKey: `quorum-${q1}-${q2}`,
        cfg: { ...base, dutyMode: 'quorum', q1, q2 },
        seed,
        log,
      });
      rows.push({ mode: 'quorum', dutyLabel: `(${q1},${q2})`, q1, q2, ...r });
    }
  }
  return rows;
}

export function runFast(log) {
  const rows = [];
  const base = SCENARIOS.fast.cfg;
  for (const policy of POLICIES) {
    for (const seed of SEEDS_FAST) {
      rows.push(runOne({ scenarioKey: 'fast', policyKey: policy.key, cfg: { ...base, ...policy.cfg }, seed, log }));
    }
  }
  return rows;
}

/**
 * Energy sensitivity: the poster's 3.8% claim depends on hardware constants we
 * cannot measure without a phone. We re-run the SAME duty cycle and traffic with
 * each constant scaled by 0.5x and 1.5x to show how fragile the number is.
 */
export function energySensitivity({ duty, transfersPerHour, discoveriesPerHour }) {
  const rows = [];
  const params = [
    ['awakeMa', ENERGY.awakeMa],
    ['deepSleepMa', ENERGY.deepSleepMa],
    ['scanRadioMa', ENERGY.scanRadioMa],
    ['batteryMah', ENERGY.batteryMah],
  ];
  const baselineExtra = radioEnergy({
    duty,
    hours: 12,
    transfersPerHour,
    discoveriesPerHour,
  }).extraMah;

  rows.push({
    parameter: 'none (nominal)',
    multiplier: 1,
    extraPercent: (baselineExtra / ENERGY.batteryMah) * 100,
    savingsVsContinuous: null,
  });

  const continuousExtra = radioEnergy({
    duty: 1,
    hours: 12,
    transfersPerHour,
    discoveriesPerHour,
  }).extraMah;

  for (const [name, value] of params) {
    for (const mult of [0.5, 1.5]) {
      const saved = { ...ENERGY, [name]: value * mult };
      // recompute by hand against the scaled constants
      const scanningHours = duty * 12;
      const sleepHours = 12 - scanningHours;
      const radioMah =
        (saved.awakeMa + saved.scanRadioMa) * scanningHours +
        saved.deepSleepMa * sleepHours +
        saved.advertiseMa * 12;
      const txMah = (transfersPerHour * 12 * saved.txSecondsPerMessage * saved.txMa) / 3600;
      const cpuMah = discoveriesPerHour * 12 * (saved.discoveryCpuMs / 3_600_000) * saved.cpuMa;
      const total = radioMah + txMah + cpuMah;
      const base = saved.deepSleepMa * 12;

      const contScan = (saved.awakeMa + saved.scanRadioMa) * 12 + saved.advertiseMa * 12 + txMah + cpuMah;
      const contExtra = contScan - base;

      rows.push({
        parameter: `${name} x${mult}`,
        multiplier: mult,
        extraPercent: ((total - base) / saved.batteryMah) * 100,
        savingsVsContinuous: ((contExtra - (total - base)) / contExtra) * 100,
      });
    }
  }
  return rows;
}
