// The discrete-time simulator.
//
// One tick = 1 second of street time. Every second we:
//   1. move every phone a little,
//   2. find pairs of phones that are within BLE range,
//   3. if both radios happen to be listening (their duty-cycle windows overlap),
//      let them exchange summary vectors and hand over messages.
//
// That is the whole protocol. Everything else is bookkeeping.

import { mulberry32, uniform } from '../core/rng.js';
import { makeDutyCycle, ENERGY } from '../core/model.js';
import { copiesToGive, initialCopies, orderCandidates } from '../core/policies.js';

export function defaultConfig(overrides = {}) {
  return {
    seed: 1,
    policy: 'extem',
    scenario: 'uniform', // 'uniform' | 'clusters' | 'fast'
    nodes: 40,
    area: 900, // metres, square
    range: 25, // BLE range in metres (phones in pockets, indoor-ish)
    speedMin: 0.8, // m/s (walking)
    speedMax: 1.6,
    pauseMax: 20, // seconds standing still at each waypoint
    duration: 3600, // simulated seconds
    dt: 1,
    createInterval: 120, // each node creates one message every N seconds
    ttl: 1800, // message lifetime (s)
    bufferLimit: 200, // max messages cached per phone
    sprayL: 8,
    extemL: 6,
    duty: 1, // fixed scan duty cycle (ignored when dutyMode = 'adaptive')
    dutyMode: 'fixed', // 'fixed' | 'adaptive' | 'adaptive-isolation' | 'quorum'
    dutyPeriod: 20,
    q1: 7, // quorum periods, must be coprime
    q2: 11,
    dutyMin: 0.1, // adaptive: idle node
    dutyMax: 0.6, // adaptive: node holding a full queue of undelivered messages
    dutyIdle: 0.06, // adaptive-isolation: node that has met nobody for a while
    isolatedAfter: 300, // seconds without an encounter before we consider a node isolated
    queueFull: 10, // queue size at which the controller reaches dutyMax
    battery: 1, // state of charge, 0..1 (1 = full)
    lowBattery: 0.2, // below this, halve the scan budget
    throughputBps: 8000, // effective BLE GATT throughput, bytes/s
    msgSize: 512,
    headerSize: 64,
    clusterSplit: 0.425, // 42.5% of nodes in each cluster, rest are carriers
    ...overrides,
  };
}

function pickWaypoint(node, cfg, rng) {
  const b = node.home;
  node.targetX = b.x0 + rng() * b.w;
  node.targetY = b.y0 + rng() * b.h;
}

/**
 * EXTEM's contribution: the scan duty cycle is not a constant.
 *
 * Three signals drive it:
 *   - backlog:     a node holding undelivered messages listens more;
 *   - isolation:   a node that has met nobody for a while is probably out of
 *                  contact with the network, so scanning is wasted energy;
 *   - battery:     a low battery halves the budget.
 *
 * This is the difference between "sleep 80% of the time everywhere" (which breaks
 * delivery in sparse areas) and "sleep where scanning cannot pay off".
 */
export function scanFraction(node, cfg, t = 0) {
  if (cfg.dutyMode === 'fixed') {
    return cfg.duty >= 1 ? 1 : node.duty.duty;
  }

  const load = Math.min(1, node.buffer.size / cfg.queueFull);

  if (cfg.dutyMode === 'adaptive-isolation') {
    const isolated = t - (node.lastEncounterAt ?? -1e9) > cfg.isolatedAfter;
    let d = isolated ? cfg.dutyIdle : cfg.dutyMin + (cfg.dutyMax - cfg.dutyMin) * load;
    if (cfg.battery < cfg.lowBattery) d *= 0.5;
    return Math.min(1, d);
  }

  // plain 'adaptive': backlog only
  let d = cfg.dutyMin + (cfg.dutyMax - cfg.dutyMin) * load;
  if (cfg.battery < cfg.lowBattery) d *= 0.5;
  return Math.min(1, d);
}

export function isActive(node, t, cfg) {
  if (cfg.dutyMode === 'quorum') {
    // Quorum schedule (Disco-style): a node listens at every multiple of two
    // COPRIME periods. Because the periods are coprime, any two nodes — whatever
    // their phase — are guaranteed to be awake together at least once per
    // q1*q2 seconds. Duty cycle = 1/q1 + 1/q2 - 1/(q1*q2).
    const a = node.dutyOffset;
    return (t + a) % cfg.q1 === 0 || (t + a) % cfg.q2 === 0;
  }

  const d = scanFraction(node, cfg, t);
  if (d >= 1) return true;
  const period = cfg.dutyPeriod;
  const window = Math.max(1, Math.round(d * period));
  return (t + node.dutyOffset) % period < window;
}

/** Theoretical duty cycle of the quorum schedule. */
export function quorumDuty(q1, q2) {
  return 1 / q1 + 1 / q2 - 1 / (q1 * q2);
}

function createNodes(cfg, rng) {
  const nodes = [];
  const clusterCount = cfg.scenario === 'clusters' ? 2 : 1;
  const perCluster = Math.floor(cfg.nodes * (cfg.scenario === 'clusters' ? cfg.clusterSplit : 1));
  const carrierCount = cfg.scenario === 'clusters' ? cfg.nodes - perCluster * 2 : 0;

  const homeFor = (i) => {
    if (cfg.scenario !== 'clusters') return { x0: 0, y0: 0, w: cfg.area, h: cfg.area };
    if (i < perCluster) return { x0: 50, y0: 50, w: 300, h: 300 }; // cluster A
    if (i < perCluster * 2) return { x0: cfg.area - 350, y0: 50, w: 300, h: 300 }; // cluster B
    return null; // carrier
  };

  const speedScale = cfg.scenario === 'fast' ? 4 : 1;

  for (let i = 0; i < cfg.nodes; i++) {
    const home = homeFor(i);
    const node = {
      id: i,
      x: 0,
      y: 0,
      targetX: 0,
      targetY: 0,
      speed: uniform(rng, cfg.speedMin, cfg.speedMax) * speedScale,
      pauseUntil: 0,
      isCarrier: home === null,
      home,
      buffer: new Map(), // msgId -> {msg, copies, hops}
      known: new Set(),
      duty: makeDutyCycle(cfg.duty, cfg.dutyPeriod, i * 3),
      dutyOffset: (i * 3) % cfg.dutyPeriod,
      lastEncounterAt: -1e9,
      scanTicks: 0,
      created: 0,
      delivered: 0,
      relayed: 0,
      discoveries: 0,
      bufferPeak: 0,
      drops: 0,
      expired: 0,
    };

    if (node.isCarrier) {
      node.x = uniform(rng, 100, cfg.area - 100);
      node.y = 200;
      node.routeTarget = 0;
    } else {
      node.x = home.x0 + rng() * home.w;
      node.y = home.y0 + rng() * home.h;
      pickWaypoint(node, cfg, rng);
    }
    nodes.push(node);
  }
  return { nodes, carrierCount };
}

const CLUSTER_CENTERS = (cfg) => [
  { x: 200, y: 200 },
  { x: cfg.area - 200, y: 200 },
];

function stepMovement(node, cfg, rng, t) {
  if (node.isCarrier) {
    const centers = CLUSTER_CENTERS(cfg);
    const goal = centers[node.routeTarget];
    const dx = goal.x - node.x;
    const dy = goal.y - node.y;
    const dist = Math.hypot(dx, dy);
    const step = node.speed * cfg.dt;
    if (dist <= step + 1) {
      node.x = goal.x;
      node.y = goal.y;
      // dwell at the cluster, then go to the other one
      if (node.dwellUntil === undefined) node.dwellUntil = t + 90;
      if (t >= node.dwellUntil) {
        node.routeTarget = 1 - node.routeTarget;
        node.dwellUntil = undefined;
      }
    } else {
      node.x += (dx / dist) * step;
      node.y += (dy / dist) * step;
    }
    return;
  }

  if (t < node.pauseUntil) return;
  const dx = node.targetX - node.x;
  const dy = node.targetY - node.y;
  const dist = Math.hypot(dx, dy);
  const step = node.speed * cfg.dt;
  if (dist <= step) {
    node.x = node.targetX;
    node.y = node.targetY;
    node.pauseUntil = t + rng() * cfg.pauseMax;
    pickWaypoint(node, cfg, rng);
  } else {
    node.x += (dx / dist) * step;
    node.y += (dy / dist) * step;
  }
}

function evictIfNeeded(node, cfg, now) {
  while (node.buffer.size > cfg.bufferLimit) {
    // Drop the least urgent message (most time-to-live left = least pressure).
    let worstId = null;
    let worstScore = -Infinity;
    for (const [id, entry] of node.buffer) {
      const remaining = (entry.msg.ttl ?? cfg.ttl) - (now - entry.msg.createdAt);
      const score = remaining + (entry.copies ?? 1) * 10;
      if (score > worstScore) {
        worstScore = score;
        worstId = id;
      }
    }
    if (worstId === null) break;
    node.buffer.delete(worstId);
    node.drops++;
  }
}

function putMessage(node, entry, cfg, now) {
  node.known.add(entry.msg.id);
  node.buffer.set(entry.msg.id, entry);
  evictIfNeeded(node, cfg, now);
  if (node.buffer.size > node.bufferPeak) node.bufferPeak = node.buffer.size;
}

/**
 * One rendezvous between two phones that are in range and both listening.
 * Returns the number of messages actually transferred.
 */
function exchange(a, b, now, cfg, ctx) {
  const perTick = Math.max(
    1,
    Math.floor((cfg.throughputBps * cfg.dt) / (cfg.msgSize + cfg.headerSize))
  );

  const candidatesFor = (from, to) => {
    const out = [];
    for (const entry of from.buffer.values()) {
      if (to.known.has(entry.msg.id)) continue;
      const give = copiesToGive(cfg.policy, entry, to.id);
      if (give <= 0) continue;
      out.push({ entry, give });
    }
    return orderCandidates(
      cfg.policy,
      out.map((c) => c.entry),
      now
    ).map((entry) => out.find((c) => c.entry === entry));
  };

  const aCands = candidatesFor(a, b);
  const bCands = candidatesFor(b, a);
  if (aCands.length === 0 && bCands.length === 0) return 0;

  let budget = perTick;
  let moved = 0;

  const send = (from, to, cand) => {
    const { entry, give } = cand;
    const msg = entry.msg;
    if (budget <= 0) return false;

    let copiesForPeer = give;
    if (cfg.policy !== 'epidemic' && give !== Infinity) {
      const c = entry.copies ?? 1;
      if (c > 1) {
        const handover = Math.floor(c / 2);
        entry.copies = c - handover;
        copiesForPeer = handover;
      }
    }

    const hops = (entry.hops ?? 0) + 1;
    putMessage(to, { msg, copies: copiesForPeer === Infinity ? 1 : copiesForPeer, hops }, cfg, now);
    from.relayed++;
    ctx.transmissions++;
    budget--;
    moved++;

    if (msg.dst === to.id) {
      ctx.ledger.get(msg.id).delivered = true;
      ctx.ledger.get(msg.id).latency = now - msg.createdAt;
      ctx.ledger.get(msg.id).hopsUsed = hops;
      to.delivered++;
      to.buffer.delete(msg.id); // delivered, stop forwarding
    }
    return true;
  };

  // Half-duplex radio: alternate directions so neither side starves.
  let ai = 0;
  let bi = 0;
  while (budget > 0 && (ai < aCands.length || bi < bCands.length)) {
    if (ai < aCands.length) send(a, b, aCands[ai++]);
    if (budget > 0 && bi < bCands.length) send(b, a, bCands[bi++]);
  }
  return moved;
}

export function runTrial(cfgIn) {
  const cfg = defaultConfig(cfgIn);
  const rng = mulberry32((cfg.seed * 2654435761) >>> 0);
  const { nodes } = createNodes(cfg, rng);

  const ledger = new Map(); // msgId -> {src, dst, createdAt, delivered, latency, hopsUsed}
  const ctx = { ledger, transmissions: 0 };
  const trace = new Map(); // "a-b" -> {a,b,start,end}
  const intervals = [];
  const active = new Map(); // "a-b" -> start time
  const meeting = new Map(); // "a-b" -> was a link actually established this encounter?
  const touched = new Set();

  let msgSeq = 0;
  const range2 = cfg.range * cfg.range;

  for (let t = 0; t < cfg.duration; t += cfg.dt) {
    // 1. mobility
    for (const n of nodes) {
      stepMovement(n, cfg, rng, t);
      if (isActive(n, t, cfg)) n.scanTicks++;
    }

    // 2. message generation (staggered so the network is not synchronised)
    for (const n of nodes) {
      if ((t + n.id * 7) % cfg.createInterval !== 0) continue;
      let dst = Math.floor(rng() * cfg.nodes);
      if (dst === n.id) dst = (dst + 1) % cfg.nodes;
      const msg = {
        id: `m${++msgSeq}`,
        src: n.id,
        dst,
        createdAt: t,
        ttl: cfg.ttl,
        size: cfg.msgSize,
      };
      ledger.set(msg.id, {
        id: msg.id,
        src: n.id,
        dst,
        createdAt: t,
        ttl: msg.ttl,
        delivered: false,
        latency: null,
        hopsUsed: null,
      });
      putMessage(n, { msg, copies: initialCopies(cfg.policy, cfg), hops: 0 }, cfg, t);
      n.created++;
    }

    // 3. proximity + rendezvous
    touched.clear();
    for (let i = 0; i < nodes.length; i++) {
      const a = nodes[i];
      for (let j = i + 1; j < nodes.length; j++) {
        const b = nodes[j];
        const dx = a.x - b.x;
        const dy = a.y - b.y;
        if (dx * dx + dy * dy > range2) continue;

        const key = `${i}-${j}`;
        touched.add(key);
        if (!active.has(key)) {
          active.set(key, t);
          meeting.set(key, false);
        }
        if (isActive(a, t, cfg) && isActive(b, t, cfg)) {
          if (!meeting.get(key)) {
            meeting.set(key, true);
            a.discoveries++;
            b.discoveries++;
            a.lastEncounterAt = t;
            b.lastEncounterAt = t;
          }
          exchange(a, b, t, cfg, ctx);
        }
      }
    }
    for (const [key, start] of active) {
      if (touched.has(key)) continue;
      const [ai, bi] = key.split('-').map(Number);
      intervals.push({ a: ai, b: bi, s: start, e: t });
      active.delete(key);
      meeting.delete(key);
    }

    // 4. TTL expiry
    for (const n of nodes) {
      if (n.buffer.size === 0) continue;
      for (const [id, entry] of n.buffer) {
        if (t - entry.msg.createdAt > entry.msg.ttl) {
          n.buffer.delete(id);
          n.expired++;
        }
      }
    }
  }

  for (const [key, start] of active) {
    const [ai, bi] = key.split('-').map(Number);
    intervals.push({ a: ai, b: bi, s: start, e: cfg.duration });
  }

  // ---- aggregate ----
  const messages = [...ledger.values()];

  // Fair accounting: a message created in the last TTL seconds could not possibly
  // finish its lifetime, so counting it as "not delivered" would punish slow
  // (low-duty) policies for an artefact of the experiment window.
  const cutoff = cfg.censorTtl ? cfg.duration - cfg.ttl : Infinity;
  for (const m of messages) m.eligible = m.createdAt <= cutoff;
  const eligible = messages.filter((m) => m.eligible);

  const delivered = eligible.filter((m) => m.delivered);
  const latencies = delivered.map((m) => m.latency).sort((x, y) => x - y);

  const pct = (p) => (latencies.length ? latencies[Math.min(latencies.length - 1, Math.floor(p * latencies.length))] : null);

  const totals = {
    created: messages.length,
    eligible: eligible.length,
    delivered: delivered.length,
    pdr: eligible.length ? delivered.length / eligible.length : 0,
    transmissions: ctx.transmissions,
    overheadRatio: delivered.length ? ctx.transmissions / delivered.length : 0,
    latencyMean: latencies.length ? latencies.reduce((s, v) => s + v, 0) / latencies.length : null,
    latencyMedian: pct(0.5),
    latencyP95: pct(0.95),
    avgBufferPeak: nodes.reduce((s, n) => s + n.bufferPeak, 0) / nodes.length,
    drops: nodes.reduce((s, n) => s + n.drops, 0),
    expired: nodes.reduce((s, n) => s + n.expired, 0),
    discoveriesPerHour: (nodes.reduce((s, n) => s + n.discoveries, 0) / cfg.duration) * 3600,
    transfersPerHour: (ctx.transmissions / cfg.duration) * 3600,
    avgDuty: nodes.reduce((s, n) => s + n.scanTicks, 0) / (nodes.length * cfg.duration),
  };

  return {
    cfg,
    nodes: nodes.length,
    messages,
    totals,
    intervals,
    perNode: nodes.map((n) => ({
      id: n.id,
      relayed: n.relayed,
      delivered: n.delivered,
      created: n.created,
      bufferPeak: n.bufferPeak,
      discoveries: n.discoveries,
    })),
  };
}
