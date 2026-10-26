import { runMain, runSweep, runFast, energySensitivity, SEEDS_MAIN, SEEDS_SWEEP, SEEDS_FAST, POLICIES } from './experiments.js';
import { buildReport } from './report.js';
import { mean } from './stats.js';

const log = (s) => process.stdout.write(s + '\n');
const t0 = Date.now();

log('');
log('EXTEM-Mesh — reproducible experiment suite');
log('==========================================');
log(`policies: ${POLICIES.map((p) => p.key).join(', ')}`);
log('');

log('[1/4] main comparison (district + partition)');
const main = runMain(log);

log('');
log('[2/4] duty-cycle sweep (district)');
const sweep = runSweep(log);

log('');
log('[3/4] fast-mobility boundary condition');
const fast = runFast(log);

log('');
log('[4/4] energy sensitivity + report');

const proposed = main.filter((r) => r.policy === 'extem-quorum' && r.scenario === 'district');
const sensitivity = energySensitivity({
  duty: mean(proposed.map((r) => r.avgDuty)),
  transfersPerHour: mean(proposed.map((r) => r.transfersPerHour)),
  discoveriesPerHour: mean(proposed.map((r) => r.discoveriesPerHour)),
});

const meta = {
  generatedAt: new Date().toISOString(),
  nodeVersion: process.version,
  seeds: { main: SEEDS_MAIN, sweep: SEEDS_SWEEP, fast: SEEDS_FAST },
  runs: main.length + sweep.length + fast.length,
};

const res = buildReport({ main, sweep, fast, sensitivity, meta });

log(`runs: ${meta.runs}   wall clock: ${((Date.now() - t0) / 1000).toFixed(0)} s`);
log('');
log('KEY NUMBERS (sparse district)');
log('-----------------------------');
for (const p of POLICIES) {
  const s = res.dSum[p.key];
  if (!s) continue;
  log(
    `${p.short.padEnd(14)} PDR ${(100 * s.pdr.mean).toFixed(1).padStart(5)}%  ` +
      `duty ${(100 * s.duty.mean).toFixed(0).padStart(3)}%  ` +
      `extra ${s.extra.mean.toFixed(1).padStart(4)}%/12h  ` +
      `median latency ${String(Math.round(s.latencyMedian)).padStart(4)} s`
  );
}
log('');
for (const c of res.claims) {
  log(`${c.verdict.padEnd(14)} ${c.claim}\n               measured: ${c.measured} (target ${c.target})`);
}
log('');
log('written: results/REPORT.md, results/raw-runs.csv, results/charts/*.svg');
log('');
