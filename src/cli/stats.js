// Statistics helpers. A single run of a random simulation proves nothing; we run
// each configuration with many seeds and report the mean with a 95% confidence
// interval, then test the difference between policies with a permutation test.

export function mean(xs) {
  if (!xs.length) return 0;
  return xs.reduce((s, v) => s + v, 0) / xs.length;
}

export function sd(xs) {
  if (xs.length < 2) return 0;
  const m = mean(xs);
  const v = xs.reduce((s, x) => s + (x - m) ** 2, 0) / (xs.length - 1);
  return Math.sqrt(v);
}

/** 95% CI using the t distribution (t approximation for small n). */
export function ci95(xs) {
  const n = xs.length;
  if (n < 2) return { mean: mean(xs), lo: mean(xs), hi: mean(xs), half: 0, n };
  const m = mean(xs);
  const s = sd(xs);
  const se = s / Math.sqrt(n);
  const t = tCritical(n - 1);
  return { mean: m, lo: m - t * se, hi: m + t * se, half: t * se, n, sd: s };
}

// Two-sided 95% t critical values, df 1..30, then 40, 60, 120, infinity.
const T_TABLE = {
  1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306,
  9: 2.262, 10: 2.228, 11: 2.201, 12: 2.179, 13: 2.16, 14: 2.145, 15: 2.131,
  16: 2.12, 17: 2.11, 18: 2.101, 19: 2.093, 20: 2.086, 21: 2.08, 22: 2.074,
  23: 2.069, 24: 2.064, 25: 2.06, 26: 2.056, 27: 2.052, 28: 2.048, 29: 2.045,
  30: 2.042, 40: 2.021, 60: 2.0, 120: 1.98,
};

function tCritical(df) {
  if (df <= 0) return 0;
  if (T_TABLE[df]) return T_TABLE[df];
  if (df < 40) return T_TABLE[30];
  if (df < 60) return T_TABLE[40];
  if (df < 120) return T_TABLE[60];
  return 1.96;
}

/** Percentile of a sorted or unsorted numeric array. */
export function quantile(xs, p) {
  if (!xs.length) return null;
  const a = xs.slice().sort((x, y) => x - y);
  const i = Math.min(a.length - 1, Math.max(0, Math.floor(p * (a.length - 1))));
  return a[i];
}

/**
 * Bootstrap 95% CI for the difference of two means, plus a permutation p-value.
 * No normality assumption: we just resample the seeds we actually ran.
 */
export function compare(a, b, { resamples = 4000, rng = Math.random } = {}) {
  const diff = mean(a) - mean(b);
  const boots = [];
  for (let i = 0; i < resamples; i++) {
    const ra = [];
    const rb = [];
    for (let k = 0; k < a.length; k++) ra.push(a[Math.floor(rng() * a.length)]);
    for (let k = 0; k < b.length; k++) rb.push(b[Math.floor(rng() * b.length)]);
    boots.push(mean(ra) - mean(rb));
  }
  boots.sort((x, y) => x - y);
  const lo = boots[Math.floor(0.025 * boots.length)];
  const hi = boots[Math.floor(0.975 * boots.length)];

  // permutation test on the pooled sample
  const pool = [...a, ...b];
  let hits = 0;
  for (let i = 0; i < resamples; i++) {
    for (let k = pool.length - 1; k > 0; k--) {
      const j = Math.floor(rng() * (k + 1));
      [pool[k], pool[j]] = [pool[j], pool[k]];
    }
    const pa = pool.slice(0, a.length);
    const pb = pool.slice(a.length);
    if (Math.abs(mean(pa) - mean(pb)) >= Math.abs(diff)) hits++;
  }

  return { diff, lo, hi, p: (hits + 1) / (resamples + 1) };
}

export function fmt(x, digits = 2) {
  if (x === null || x === undefined || Number.isNaN(x)) return '—';
  return Number(x).toFixed(digits);
}
