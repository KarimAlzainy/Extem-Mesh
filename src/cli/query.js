import fs from 'node:fs';

const txt = fs.readFileSync('results/raw-runs.csv', 'utf8');
const rows = txt
  .trim()
  .split(/\r?\n/)
  .map((l) => l.match(/"[^"]*"|[^,]+/g).map((s) => s.replace(/^"|"$/g, '')));
const [header, ...body] = rows;
const idx = Object.fromEntries(header.map((k, i) => [k, i]));

const agg = (scenario, policy, field) => {
  const rs = body.filter((r) => r[idx.scenario] === scenario && r[idx.policy] === policy);
  return rs.reduce((s, r) => s + Number(r[idx[field]]), 0) / rs.length;
};

for (const scenario of ['district', 'partition', 'fast']) {
  for (const policy of ['epidemic', 'spray', 'extem-cont', 'extem-fixed35', 'extem-quorum']) {
    const n = body.filter((r) => r[idx.scenario] === scenario && r[idx.policy] === policy).length;
    if (!n) continue;
    console.log(
      scenario.padEnd(10),
      policy.padEnd(14),
      'n=' + n,
      'pdr=' + (100 * agg(scenario, policy, 'pdr')).toFixed(1),
      'p50=' + agg(scenario, policy, 'latencyMedian').toFixed(0),
      'p95=' + agg(scenario, policy, 'latencyP95').toFixed(0),
      'reach=' + (100 * agg(scenario, policy, 'reachableShare')).toFixed(1)
    );
  }
}
