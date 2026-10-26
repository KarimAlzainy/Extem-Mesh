// "How many hops away was the destination, and could it be reached at all within
// the message's own lifetime?"
//
// This is the honest way to talk about multi-hop. We do NOT assume a hop count and
// we do NOT credit a path that only becomes possible hours after the message died.
// We take the recorded contact trace (who met whom, and when), and from the moment
// the message was created we compute the earliest possible arrival at every other
// phone, only using contacts that happen before the message expires.
//
// That is a time-respecting (temporal) shortest path — the standard DTN metric.

const UNREACHABLE = 999999;
const BIN_SECONDS = 60;

/**
 * @param {Array<{a:number,b:number,s:number,e:number}>} intervals contact intervals
 * @param {number} nodeCount
 * @param {Array<{id:string,src:number,dst:number,createdAt:number,ttl:number}>} messages
 * @returns {Map<string,{hops:number,arrival:number,reachable:boolean,deadlineLatency:number|null}>}
 */
export function computeMessageHops(intervals, nodeCount, messages) {
  const sorted = intervals.slice().sort((x, y) => x.s - y.s);

  // bin contacts by start second so each message only scans its own lifetime
  const bins = new Map();
  for (const iv of sorted) {
    const k = Math.floor(iv.s / BIN_SECONDS);
    if (!bins.has(k)) bins.set(k, []);
    bins.get(k).push(iv);
  }
  const maxBin = sorted.length ? Math.floor(sorted[sorted.length - 1].s / BIN_SECONDS) : -1;

  const out = new Map();
  const arrival = new Float64Array(nodeCount);
  const hop = new Int32Array(nodeCount);

  for (const m of messages) {
    arrival.fill(Infinity);
    hop.fill(UNREACHABLE);
    arrival[m.src] = m.createdAt;
    hop[m.src] = 0;

    const deadline = m.createdAt + (m.ttl ?? Infinity);
    const from = Math.floor(m.createdAt / BIN_SECONDS);
    const to = Math.min(maxBin, Math.floor(deadline / BIN_SECONDS));

    for (let b = from; b <= to; b++) {
      const list = bins.get(b);
      if (!list) continue;
      for (const iv of list) {
        if (iv.s > deadline) break;
        if (iv.e < m.createdAt) continue;
        const { a, b: bb, s, e } = iv;
        if (arrival[a] <= e && arrival[a] <= deadline) {
          const cand = Math.max(arrival[a], s);
          if (cand < arrival[bb]) {
            arrival[bb] = cand;
            hop[bb] = hop[a] + 1;
          }
        }
        if (arrival[bb] <= e && arrival[bb] <= deadline) {
          const cand = Math.max(arrival[bb], s);
          if (cand < arrival[a]) {
            arrival[a] = cand;
            hop[a] = hop[bb] + 1;
          }
        }
      }
    }

    const reached = Number.isFinite(arrival[m.dst]);
    out.set(m.id, {
      hops: hop[m.dst],
      arrival: arrival[m.dst],
      reachable: reached,
      deadlineLatency: reached ? arrival[m.dst] - m.createdAt : null,
    });
  }
  return out;
}

export const HOP_BUCKETS = ['1', '2', '3', '4', '5', '6+', 'unreachable'];

export function hopBucket(h, reachable = true) {
  if (!reachable || h >= UNREACHABLE) return 'unreachable';
  if (h >= 6) return '6+';
  return String(Math.max(1, h));
}

/** Attach hop distance, feasibility and best-possible latency to every message. */
export function annotateMessages(messages, hopMap) {
  return messages.map((m) => {
    const row = hopMap.get(m.id) ?? { hops: UNREACHABLE, reachable: false, deadlineLatency: null };
    return {
      ...m,
      hops: row.hops,
      reachable: row.reachable,
      bestLatency: row.deadlineLatency,
      hopBucket: hopBucket(row.hops, row.reachable),
    };
  });
}
