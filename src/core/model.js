// Physical constants used by the energy model.
// EVERY number here is an assumption. They are listed in docs/HOW_IT_WORKS.md and
// defended in docs/JUDGE_QA.md. If a judge challenges one, we run the sensitivity
// sweep (src/cli/energy-table.js) and show the result does/doesn't change.

export const ENERGY = {
  // Phone battery we model (typical mid-range Android).
  batteryMah: 4000,

  // Current draw, in milliamps (mA). Source: vendor BLE datasheets, Android
  // Battery Historian traces, and published BLE duty-cycle studies.
  deepSleepMa: 12, // phone idle, screen off, radios asleep
  awakeMa: 45, // CPU awake (scanning keeps the phone out of deep sleep)
  scanRadioMa: 5.5, // BLE controller in low-power scan mode
  advertiseMa: 0.8, // BLE advertising at ~1 s interval (very cheap)
  txMa: 15, // BLE connection active / data transfer
  cpuMa: 350, // CPU busy during processing bursts

  // Per discovered advertisement: decrypt/verify/log. Measured on-device this is
  // ~1-2 ms. We use 1.5 ms.
  discoveryCpuMs: 1.5,

  // Time on air for one 512 B message at our assumed link throughput.
  txSecondsPerMessage: (512 + 64) / 8000,
};

/**
 * Energy for one radio policy over `hours`.
 *
 * Model, in plain words:
 *   - While scanning, the phone is AWAKE (45 mA) and the BLE radio scans (5.5 mA).
 *   - While not scanning, the phone is in DEEP SLEEP (12 mA).
 *   - Advertising runs all the time and is nearly free (0.8 mA).
 *   - Every message transfer costs 15 mA for the time on air.
 *   - Every discovered advertisement costs a small CPU burst.
 *
 * @param {object} o
 * @param {number} o.duty         fraction of time spent scanning (0..1)
 * @param {number} o.hours        window length (usually 12)
 * @param {number} [o.transfersPerHour]
 * @param {number} [o.discoveriesPerHour]
 * @returns {{radioMah:number, txMah:number, cpuMah:number, totalMah:number,
 *            baselineMah:number, extraMah:number, extraPercent:number, totalPercent:number}}
 */
export function radioEnergy(o) {
  const {
    duty,
    hours,
    batteryMah = ENERGY.batteryMah,
    transfersPerHour = 0,
    discoveriesPerHour = 0,
  } = o;

  const d = Math.min(1, Math.max(0, duty));
  const scanningHours = d * hours;
  const sleepingHours = hours - scanningHours;

  // mA * hours = mAh
  const radioMah =
    (ENERGY.awakeMa + ENERGY.scanRadioMa) * scanningHours +
    ENERGY.deepSleepMa * sleepingHours +
    ENERGY.advertiseMa * hours;

  const txMah =
    (transfersPerHour * hours * ENERGY.txSecondsPerMessage * ENERGY.txMa) / 3600;

  const cpuMah =
    (discoveriesPerHour * hours * (ENERGY.discoveryCpuMs / 3_600_000)) * ENERGY.cpuMa;

  const totalMah = radioMah + txMah + cpuMah;
  const baselineMah = ENERGY.deepSleepMa * hours; // same phone, mesh app not installed

  return {
    radioMah,
    txMah,
    cpuMah,
    totalMah,
    baselineMah,
    extraMah: totalMah - baselineMah,
    extraPercent: ((totalMah - baselineMah) / batteryMah) * 100,
    totalPercent: (totalMah / batteryMah) * 100,
  };
}

/** Percentage energy saved relative to a reference extra draw. */
export function percentSaved(baselineExtraMah, candidateExtraMah) {
  if (baselineExtraMah <= 0) return 0;
  return ((baselineExtraMah - candidateExtraMah) / baselineExtraMah) * 100;
}

/** Rendezvous (scan) schedule for one node. */
export function makeDutyCycle(duty, period = 20, offset = 0) {
  if (duty >= 1) return { duty: 1, period: 1, window: 1, offset: 0 };
  const window = Math.max(1, Math.round(duty * period));
  return { duty: window / period, period, window, offset: offset % period };
}

/** Is the radio listening during this 1-second tick? */
export function radioActive(node, t) {
  const { period, window, offset } = node.duty;
  return (t + offset) % period < window;
}
