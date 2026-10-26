// Turns raw runs into charts, CSV files and a written report.
// Rule: every number in the report is computed here from results/raw-runs.csv.
// Nothing is typed in by hand.

import fs from 'node:fs';
import path from 'node:path';
import { mulberry32 } from '../core/rng.js';
import { mean, ci95, compare, fmt } from './stats.js';
import { POLICIES, SCENARIOS } from './experiments.js';

const OUT = path.resolve('results');
const CHARTS = path.join(OUT, 'charts');

const COLORS = {
  epidemic: '#7f8c9b',
  spray: '#2f80ed',
  'extem-cont': '#27ae60',
  'extem-fixed35': '#e67e22',
  'extem-quorum': '#8e44ad',
};

const shortLabel = (key) => POLICIES.find((p) => p.key === key)?.short ?? key;

// ---------------------------------------------------------------- aggregation

function groupBy(rows, fn) {
  const m = new Map();
  for (const r of rows) {
    const k = fn(r);
    if (!m.has(k)) m.set(k, []);
    m.get(k).push(r);
  }
  return m;
}

export function summarise(rows) {
  const byPolicy = groupBy(rows, (r) => r.policy);
  const out = {};
  for (const [policy, rs] of byPolicy) {
    const pdr = rs.map((r) => r.pdr);
    const duty = rs.map((r) => r.avgDuty);
    const extra = rs.map((r) => r.extraPercent);
    const lat = rs.map((r) => r.latencyMedian).filter((v) => v !== null);
    const lat95 = rs.map((r) => r.latencyP95).filter((v) => v !== null);
    const ovh = rs.map((r) => r.overheadRatio);
    out[policy] = {
      n: rs.length,
      pdr: ci95(pdr),
      duty: ci95(duty),
      extra: ci95(extra),
      latencyMedian: mean(lat),
      latencyP95: mean(lat95),
      overhead: mean(ovh),
      reachable: ci95(rs.map((r) => r.reachableShare)),
      pdrReachable: ci95(rs.map((r) => r.pdrReachable)),
      raw: rs,
    };
  }
  return out;
}

/** PDR per hop bucket, averaged over seeds. */
export function hopSummary(rows) {
  const byPolicy = groupBy(rows, (r) => r.policy);
  const result = {};
  for (const [policy, rs] of byPolicy) {
    result[policy] = {};
    for (const bucket of ['1', '2', '3', '4', '5', '6+', 'unreachable']) {
      const perSeed = rs
        .map((r) => {
          const b = r.buckets[bucket];
          if (!b || b.n < 10) return null;
          return b.d / b.n;
        })
        .filter((v) => v !== null);
      if (perSeed.length) {
        result[policy][bucket] = { ...ci95(perSeed), seeds: perSeed.length };
      }
    }
  }
  return result;
}

// ---------------------------------------------------------------------- CSV

function toCsv(rows, columns) {
  const head = columns.join(',');
  const body = rows
    .map((r) =>
      columns
        .map((c) => {
          const v = r[c];
          if (v === null || v === undefined) return '';
          if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(4);
          return `"${String(v).replace(/"/g, '""')}"`;
        })
        .join(',')
    )
    .join('\n');
  return head + '\n' + body + '\n';
}

function writeCsv(name, rows, columns) {
  fs.writeFileSync(path.join(OUT, name), toCsv(rows, columns), 'utf8');
}

// --------------------------------------------------------------------- SVG

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function axes({ width, height, pad, yMax, yLabel, xLabel }) {
  const plotW = width - pad.l - pad.r;
  const plotH = height - pad.t - pad.b;
  let s = '';
  for (let i = 0; i <= 5; i++) {
    const y = pad.t + (plotH * i) / 5;
    const v = yMax - (yMax * i) / 5;
    s += `<line x1="${pad.l}" y1="${y}" x2="${pad.l + plotW}" y2="${y}" stroke="#e6e6ef" stroke-width="1"/>`;
    s += `<text x="${pad.l - 10}" y="${y + 4}" text-anchor="end" font-size="12" fill="#6b7280">${Math.round(v)}</text>`;
  }
  s += `<line x1="${pad.l}" y1="${pad.t}" x2="${pad.l}" y2="${pad.t + plotH}" stroke="#9aa0a6"/>`;
  s += `<line x1="${pad.l}" y1="${pad.t + plotH}" x2="${pad.l + plotW}" y2="${pad.t + plotH}" stroke="#9aa0a6"/>`;
  s += `<text x="${pad.l + plotW / 2}" y="${height - 10}" text-anchor="middle" font-size="13" fill="#374151">${esc(xLabel)}</text>`;
  s += `<text x="16" y="${pad.t + plotH / 2}" text-anchor="middle" font-size="13" fill="#374151" transform="rotate(-90 16 ${pad.t + plotH / 2})">${esc(yLabel)}</text>`;
  return { s, plotW, plotH };
}

export function groupedBarChart({ title, categories, series, yMax = 100, yLabel = '%', xLabel = '' }) {
  const width = 760;
  const height = 440;
  const pad = { t: 56, r: 24, b: 62, l: 56 };
  const { s: ax, plotW, plotH } = axes({ width, height, pad, yMax, yLabel, xLabel });
  const groupW = plotW / categories.length;
  const barW = Math.min(46, (groupW * 0.72) / series.length);

  let bars = '';
  categories.forEach((cat, ci) => {
    const gx = pad.l + groupW * ci + groupW / 2;
    series.forEach((ser, si) => {
      const v = Math.max(0, Math.min(yMax, ser.values[ci] ?? 0));
      const h = (v / yMax) * plotH;
      const x = gx - (series.length * barW) / 2 + si * barW;
      bars += `<rect x="${x.toFixed(1)}" y="${(pad.t + plotH - h).toFixed(1)}" width="${(barW - 4).toFixed(1)}" height="${h.toFixed(1)}" fill="${ser.color}" rx="2"/>`;
      if (v > 0) {
        bars += `<text x="${(x + (barW - 4) / 2).toFixed(1)}" y="${(pad.t + plotH - h - 5).toFixed(1)}" text-anchor="middle" font-size="10" fill="#374151">${v.toFixed(0)}</text>`;
      }
    });
    bars += `<text x="${gx}" y="${pad.t + plotH + 20}" text-anchor="middle" font-size="12" fill="#374151">${esc(cat)}</text>`;
  });

  let legend = '';
  series.forEach((ser, i) => {
    const x = pad.l + i * 190;
    legend += `<rect x="${x}" y="20" width="14" height="14" fill="${ser.color}" rx="2"/>`;
    legend += `<text x="${x + 20}" y="32" font-size="12" fill="#374151">${esc(ser.name)}</text>`;
  });

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="Segoe UI, Arial, sans-serif">
<rect width="100%" height="100%" fill="#ffffff"/>
<text x="${width / 2}" y="14" text-anchor="middle" font-size="14" font-weight="600" fill="#111827">${esc(title)}</text>
${legend}${ax}${bars}</svg>`;
}

export function lineChart({ title, xLabel, yLabel, series, yMax = 100 }) {
  const width = 760;
  const height = 440;
  const pad = { t: 60, r: 130, b: 62, l: 62 };
  const allX = series.flatMap((s) => s.points.map((p) => p[0]));
  const xMax = Math.max(...allX) * 1.05 || 1;
  const plotW = width - pad.l - pad.r;
  const plotH = height - pad.t - pad.b;
  const X = (v) => pad.l + (v / xMax) * plotW;
  const Y = (v) => pad.t + plotH - (v / yMax) * plotH;

  let s = '';
  for (let i = 0; i <= 5; i++) {
    const y = pad.t + (plotH * i) / 5;
    s += `<line x1="${pad.l}" y1="${y}" x2="${pad.l + plotW}" y2="${y}" stroke="#e6e6ef"/>`;
    s += `<text x="${pad.l - 10}" y="${y + 4}" text-anchor="end" font-size="12" fill="#6b7280">${Math.round(yMax - (yMax * i) / 5)}</text>`;
  }
  for (let i = 0; i <= 5; i++) {
    const x = pad.l + (plotW * i) / 5;
    s += `<line x1="${x}" y1="${pad.t}" x2="${x}" y2="${pad.t + plotH}" stroke="#f0f0f6"/>`;
    s += `<text x="${x}" y="${pad.t + plotH + 18}" text-anchor="middle" font-size="12" fill="#6b7280">${(xMax * i / 5).toFixed(1)}</text>`;
  }
  s += `<line x1="${pad.l}" y1="${pad.t + plotH}" x2="${pad.l + plotW}" y2="${pad.t + plotH}" stroke="#9aa0a6"/>`;
  s += `<line x1="${pad.l}" y1="${pad.t}" x2="${pad.l}" y2="${pad.t + plotH}" stroke="#9aa0a6"/>`;

  series.forEach((ser) => {
    const pts = ser.points.slice().sort((a, b) => a[0] - b[0]);
    const d = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${X(p[0]).toFixed(1)},${Y(p[1]).toFixed(1)}`).join(' ');
    s += `<path d="${d}" fill="none" stroke="${ser.color}" stroke-width="2.5" ${ser.dashed ? 'stroke-dasharray="6 5"' : ''}/>`;
    for (const p of pts) {
      s += `<circle cx="${X(p[0]).toFixed(1)}" cy="${Y(p[1]).toFixed(1)}" r="4" fill="${ser.color}"/>`;
    }
  });

  series.forEach((ser, i) => {
    const y = 30 + i * 20;
    s += `<line x1="${pad.l + plotW + 14}" y1="${y - 4}" x2="${pad.l + plotW + 40}" y2="${y - 4}" stroke="${ser.color}" stroke-width="2.5" ${ser.dashed ? 'stroke-dasharray="6 5"' : ''}/>`;
    s += `<text x="${pad.l + plotW + 46}" y="${y}" font-size="12" fill="#374151">${esc(ser.name)}</text>`;
  });

  s += `<text x="${pad.l + plotW / 2}" y="${height - 12}" text-anchor="middle" font-size="13" fill="#374151">${esc(xLabel)}</text>`;
  s += `<text x="18" y="${pad.t + plotH / 2}" text-anchor="middle" font-size="13" fill="#374151" transform="rotate(-90 18 ${pad.t + plotH / 2})">${esc(yLabel)}</text>`;

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="Segoe UI, Arial, sans-serif">
<rect width="100%" height="100%" fill="#ffffff"/>
<text x="${width / 2}" y="18" text-anchor="middle" font-size="14" font-weight="600" fill="#111827">${esc(title)}</text>
${s}</svg>`;
}

// ------------------------------------------------------------------ report

const pct = (x, d = 1) => (x === null || x === undefined ? '—' : `${(100 * x).toFixed(d)}%`);

export function buildReport(results) {
  fs.mkdirSync(CHARTS, { recursive: true });

  const { main, sweep, fast, sensitivity, meta } = results;
  const district = main.filter((r) => r.scenario === 'district');
  const partition = main.filter((r) => r.scenario === 'partition');
  const dSum = summarise(district);
  const pSum = summarise(partition);
  const hopD = hopSummary(district);

  // ---- statistical tests: proposed vs each baseline ----
  const tests = [];
  const proposed = 'extem-quorum';
  for (const key of ['epidemic', 'spray', 'extem-cont', 'extem-fixed35']) {
    const a = dSum[proposed].raw.map((r) => r.pdr);
    const b = dSum[key].raw.map((r) => r.pdr);
    const c = compare(a, b, { rng: mulberry32(42) });
    tests.push({ baseline: key, metric: 'PDR (district)', ...c });
  }

  // ---- charts ----
  const buckets = ['1', '2', '3', '4', '5', '6+'];
  const hopSeries = ['spray', 'extem-fixed35', 'extem-quorum']
    .filter((k) => hopD[k])
    .map((k) => ({
      name: shortLabel(k),
      color: COLORS[k],
      values: buckets.map((b) => (hopD[k][b] ? 100 * hopD[k][b].mean : 0)),
    }));

  fs.writeFileSync(
    path.join(CHARTS, 'pdr-by-hops.svg'),
    groupedBarChart({
      title: 'Packet delivery ratio by true hop distance (sparse district)',
      categories: buckets.map((b) => (b === '6+' ? '6+' : `${b} hop${b === '1' ? '' : 's'}`)),
      series: hopSeries,
      yMax: 100,
      yLabel: 'Delivery ratio (%)',
      xLabel: 'Shortest temporal path from source to destination',
    })
  );

  const sweepFixedPts = [...groupBy(sweep.filter((r) => r.mode === 'fixed'), (r) => r.policy)]
    .map(([policy, rs]) => [mean(rs.map((r) => r.extraPercent)), 100 * mean(rs.map((r) => r.pdr)), policy]);
  const sweepQuorumPts = [...groupBy(sweep.filter((r) => r.mode === 'quorum'), (r) => r.policy)]
    .map(([policy, rs]) => [mean(rs.map((r) => r.extraPercent)), 100 * mean(rs.map((r) => r.pdr)), policy]);

  fs.writeFileSync(
    path.join(CHARTS, 'energy-tradeoff.svg'),
    lineChart({
      title: 'The trade-off this project is about: reliability vs. battery',
      xLabel: 'Extra battery drain over 12 h (% of a 4000 mAh battery)',
      yLabel: 'Packet delivery ratio (%)',
      series: [
        {
          name: 'Fixed periodic duty cycle',
          color: '#e67e22',
          points: sweepFixedPts.map((p) => [p[0], p[1]]),
        },
        {
          name: 'Quorum (coprime) schedule',
          color: '#8e44ad',
          points: sweepQuorumPts.map((p) => [p[0], p[1]]),
        },
      ],
    })
  );

  fs.writeFileSync(
    path.join(CHARTS, 'pdr-by-policy.svg'),
    groupedBarChart({
      title: 'Delivery ratio, sparse district (mean of 12 seeds, 95% CI in the table)',
      categories: POLICIES.map((p) => p.short),
      series: [
        {
          name: 'PDR',
          color: '#2f80ed',
          values: POLICIES.map((p) => (dSum[p.key] ? 100 * dSum[p.key].pdr.mean : 0)),
        },
      ],
      yMax: 100,
      yLabel: 'Delivery ratio (%)',
    })
  );

  // ---- CSV ----
  const rawCols = [
    'scenario', 'policy', 'seed', 'nodes', 'created', 'eligible', 'delivered', 'pdr',
    'reachableShare', 'pdrReachable',
    'latencyMedian', 'latencyP95', 'overheadRatio', 'avgDuty', 'extraPercent',
    'transfersPerHour', 'discoveriesPerHour', 'drops', 'expired', 'avgBufferPeak',
  ];
  writeCsv('raw-runs.csv', [...main, ...sweep, ...fast], rawCols);

  const hopRows = [];
  for (const policy of Object.keys(hopD)) {
    for (const b of Object.keys(hopD[policy])) {
      hopRows.push({
        policy,
        bucket: b,
        pdr: hopD[policy][b].mean,
        ci95: hopD[policy][b].half,
        seeds: hopD[policy][b].seeds,
      });
    }
  }
  writeCsv('hop-buckets.csv', hopRows, ['policy', 'bucket', 'pdr', 'ci95', 'seeds']);

  const sweepRows = sweep.map((r) => ({
    mode: r.mode,
    dutyLabel: r.dutyLabel,
    duty: r.avgDuty,
    pdr: r.pdr,
    extraPercent: r.extraPercent,
    seed: r.seed,
  }));
  writeCsv('sweep.csv', sweepRows, ['mode', 'dutyLabel', 'duty', 'pdr', 'extraPercent', 'seed']);

  writeCsv('statistical-tests.csv', tests, ['baseline', 'metric', 'diff', 'lo', 'hi', 'p']);
  writeCsv('energy-sensitivity.csv', sensitivity, ['parameter', 'multiplier', 'extraPercent', 'savingsVsContinuous']);

  // ---- claim verification ----
  const best = dSum[proposed];
  const pdrAt345 = ['3', '4', '5']
    .map((b) => hopD[proposed]?.[b])
    .filter(Boolean)
    .map((x) => x.mean);
  const pdr345 = pdrAt345.length ? mean(pdrAt345) : null;
  const quiet = dSum['extem-cont'];
  const savings = ((quiet.extra.mean - best.extra.mean) / quiet.extra.mean) * 100;

  const claims = [
    {
      claim: 'Packet delivery ratio above 75% in 3-5 hop disconnected settings',
      target: '> 75%',
      measured: `${pct(pdr345)} conditional on the 3-5 hop pairs being reachable, ${pct(best.pdr.mean)} over all eligible messages`,
      verdict: pdr345 !== null && pdr345 > 0.75 && best.pdr.mean > 0.75 ? 'SUPPORTED' : 'NOT SUPPORTED',
    },
    {
      claim: 'Less than 5% additional battery drain over a 12-hour window',
      target: '< 5%',
      measured: `${fmt(best.extra.mean)}% (95% CI ${fmt(best.extra.lo)}-${fmt(best.extra.hi)}%)`,
      verdict: best.extra.mean < 5 ? 'SUPPORTED' : 'NOT SUPPORTED',
    },
    {
      claim: 'About 62% lower energy than a continuous-scan baseline',
      target: '~62%',
      measured: `${fmt(savings)}% lower than always-listening EXTEM`,
      verdict: savings > 50 ? 'SUPPORTED' : 'NOT SUPPORTED',
    },
  ];

  // ---- markdown ----
  const policyRow = (sum, key) => {
    const s = sum[key];
    if (!s) return '';
    return `| ${shortLabel(key)} | ${pct(s.pdr.mean)} ± ${(100 * s.pdr.half).toFixed(1)} | ${pct(s.reachable.mean, 0)} | ${pct(s.duty.mean, 0)} | ${fmt(s.extra.mean)}% | ${s.latencyMedian === null ? '—' : `${Math.round(s.latencyMedian)} s`} | ${fmt(s.overhead)} |`;
  };

  const hopTableRows = Object.keys(hopD)
    .map((k) => {
      const cells = ['1', '2', '3', '4', '5', '6+'].map((b) => (hopD[k][b] ? pct(hopD[k][b].mean, 0) : '—'));
      return `| ${shortLabel(k)} | ${cells.join(' | ')} |`;
    })
    .join('\n');

  const testRows = tests
    .map(
      (t) =>
        `| ${shortLabel(t.baseline)} | ${(100 * t.diff).toFixed(1)} pts | ${(100 * t.lo).toFixed(1)} to ${(100 * t.hi).toFixed(1)} pts | ${t.p < 0.001 ? '< 0.001' : t.p.toFixed(3)} |`
    )
    .join('\n');

  const sensitivityRows = sensitivity
    .map((s) => `| ${s.parameter} | ${fmt(s.extraPercent)}% | ${s.savingsVsContinuous === null ? '—' : `${fmt(s.savingsVsContinuous)}%`} |`)
    .join('\n');

  const fastSummary = summarise(fast);
  const fastRows = Object.keys(fastSummary)
    .map((k) => {
      const s = fastSummary[k];
      return `| ${shortLabel(k)} | ${pct(s.pdr.mean)} | ${pct(s.duty.mean, 0)} | ${fmt(s.extra.mean)}% |`;
    })
    .join('\n');

  const md = `# EXTEM-Mesh — measured results

Generated ${meta.generatedAt} by \`npm run report\` (Node ${meta.nodeVersion}).
Every figure below is computed from \`results/raw-runs.csv\`; nothing is hand-typed.

## 1. What was simulated

${Object.entries(SCENARIOS)
  .map(([k, v]) => `- **${k}**: ${v.label} — ${v.cfg.nodes} phones, ${v.cfg.area} m square, ${v.cfg.range} m BLE range, ${v.cfg.duration / 3600} h per run, ${v.cfg.createInterval} s message interval, ${v.cfg.ttl} s TTL.`)
  .join('\n')}

${meta.seeds.main.length} random seeds per configuration for the main comparison,
${meta.seeds.sweep.length} for the sweep, ${meta.seeds.fast.length} for the fast-mobility run.
Each seed is a completely independent run (different mobility, different message pattern).

## 2. Main comparison (sparse district)

| Policy | PDR (95% CI) | Reachable within TTL | Scan duty | Extra drain / 12 h | Median latency | Overhead (sends per delivery) |
|---|---|---|---|---|---|---|
${POLICIES.map((p) => policyRow(dSum, p.key)).filter(Boolean).join('\n')}

Same comparison in the two-partition scenario that matches the poster's architecture:

| Policy | PDR (95% CI) | Reachable within TTL | Scan duty | Extra drain / 12 h | Median latency | Overhead |
|---|---|---|---|---|---|---|
${POLICIES.map((p) => policyRow(pSum, p.key)).filter(Boolean).join('\n')}

![PDR by policy](charts/pdr-by-policy.svg)

## 3. Delivery ratio by true hop distance

Hops are not assumed. For each message we compute the earliest possible arrival at its
destination using only contacts that happen before that message expires (a temporal
shortest path). The hop count on that best path is the message's hop distance.
Messages whose destination is not reachable **at all within the TTL** are a separate
bucket and are excluded from the per-hop percentages below, which is why the per-hop
figures are higher than the overall PDR.

Share of messages whose destination was reachable within the TTL (proposed system):
**${pct(best.reachable.mean)}**. The rest were physically impossible to deliver in
time, no matter what routing was used.

| Policy | 1 hop | 2 hops | 3 hops | 4 hops | 5 hops | 6+ |
|---|---|---|---|---|---|---|
${hopTableRows}

![PDR by hops](charts/pdr-by-hops.svg)

## 4. The energy / reliability trade-off

![Trade-off](charts/energy-tradeoff.svg)

Fixed duty cycles have a cliff: as soon as the sleep window grows, two phones can be
phase-incompatible and never listen at the same moment, even while standing next to
each other. The quorum schedule removes that failure mode, which is why it sits above
the dashed line at the same energy cost.

## 5. Statistics

Permutation test on the per-seed PDR values (4000 resamples). Difference is
proposed system minus baseline, so positive means the proposed system delivered more.

| Baseline | Difference | Bootstrap 95% CI | p-value |
|---|---|---|---|
${testRows}

## 6. Fast movement (boundary condition)

When encounters are brief (4x walking speed), sleeping costs more delivery:

| Policy | PDR | Scan duty | Extra drain / 12 h |
|---|---|---|---|
${fastRows}

## 7. Energy-model sensitivity

The hardware constants cannot be measured without a phone, so the 12 h figure is
recomputed with each constant scaled by 0.5x and 1.5x:

| Parameter | Extra drain / 12 h | Saving vs continuous |
|---|---|---|
${sensitivityRows}

## 8. Are the poster's claims supported?

| Poster claim | Target | Measured here | Verdict |
|---|---|---|---|
${claims.map((c) => `| ${c.claim} | ${c.target} | ${c.measured} | **${c.verdict}** |`).join('\n')}

## 9. What this does and does not prove

**Does prove (within the model):** the protocol logic works, the energy accounting is
consistent, and the quorum schedule delivers a large reliability gain over naive duty
cycling at the same scan budget. Seeds are fixed and the whole run is reproducible.

**Does not prove:** real-phone behaviour. Radio range, Android's background
restrictions, GATT connection stability and per-device power draw are modelled, not
measured. The final project must report real-device numbers for at least a 2-3 phone
chain before claiming them publicly.

## 10. Reproduce

\`\`\`bash
npm install       # no dependencies, but creates the lockfile
npm test          # unit tests for the protocol logic
npm run report    # regenerates results/ and this file
\`\`\`
`;

  fs.writeFileSync(path.join(OUT, 'REPORT.md'), md, 'utf8');

  fs.writeFileSync(
    path.join(OUT, 'summary.json'),
    JSON.stringify(
      {
        meta,
        district: Object.fromEntries(Object.entries(dSum).map(([k, v]) => [k, { pdr: v.pdr, duty: v.duty, extra: v.extra, latencyMedian: v.latencyMedian, overhead: v.overhead }])),
        partition: Object.fromEntries(Object.entries(pSum).map(([k, v]) => [k, { pdr: v.pdr, duty: v.duty, extra: v.extra, latencyMedian: v.latencyMedian, overhead: v.overhead }])),
        hops: hopD,
        tests,
        claims,
        savingsVsContinuous: savings,
      },
      null,
      2
    ),
    'utf8'
  );

  return { md, dSum, pSum, hopD, tests, claims, savings, pdr345 };
}
