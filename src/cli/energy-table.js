// Standalone energy table. Useful when someone asks "what does 40% duty cost?"
// It uses the exact same model as the experiments (src/core/model.js).

import { radioEnergy, ENERGY } from '../core/model.js';
import { quorumDuty } from '../sim/engine.js';

const TRAFFIC = { transfersPerHour: 8500, discoveriesPerHour: 3000 };

const row = (label, duty, note = '') => {
  const e = radioEnergy({ duty, hours: 12, ...TRAFFIC });
  const cont = radioEnergy({ duty: 1, hours: 12, ...TRAFFIC });
  const save = ((cont.extraMah - e.extraMah) / cont.extraMah) * 100;
  console.log(
    label.padEnd(22),
    `${(100 * duty).toFixed(0).padStart(3)}% listening`,
    `${e.extraMah.toFixed(0).padStart(4)} mAh extra`,
    `${e.extraPercent.toFixed(2).padStart(5)}% of battery`,
    `${save.toFixed(0).padStart(3)}% saved`,
    note
  );
};

console.log('\nEXTEM-Mesh energy model — 12 hours, ' + ENERGY.batteryMah + ' mAh phone\n');
console.log('Assumed traffic: ' + TRAFFIC.transfersPerHour + ' transfers/h, ' + TRAFFIC.discoveriesPerHour + ' discoveries/h\n');

console.log('Fixed duty cycles');
for (const d of [1, 0.75, 0.5, 0.35, 0.25, 0.15, 0.1, 0.05]) row('fixed', d);

console.log('\nQuorum (coprime) schedules');
for (const [q1, q2] of [[3, 4], [4, 5], [5, 7], [7, 11], [9, 13]]) {
  row(`quorum (${q1},${q2})`, quorumDuty(q1, q2), `guaranteed overlap <= ${q1 * q2} slots`);
}

console.log('\nNote: the simulator uses 1-second slots. On hardware use 2-second slots');
console.log('(same duty, overlap guarantee doubles to 70 s) — see android/MeshNode.kt.\n');
