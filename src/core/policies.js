// Routing policies. Each policy decides: "given my buffer and what the peer already
// knows, which messages do I hand over, in what order, and how many copies do I spend?"
//
// This is the actual scientific contribution of the project: three policies compared
// under identical conditions.

export const POLICIES = ['epidemic', 'spray', 'extem'];

/**
 * Order candidates for transmission.
 * - epidemic: oldest message first (flood everything)
 * - spray:    oldest first (classic Spray-and-Wait)
 * - extem:    most urgent first (least time-to-live left), then fewest copies used
 */
export function orderCandidates(policy, entries, now) {
  const arr = entries.slice();
  if (policy === 'extem') {
    arr.sort((x, y) => {
      const ux = (x.msg.ttl ?? 0) - (now - x.msg.createdAt);
      const uy = (y.msg.ttl ?? 0) - (now - y.msg.createdAt);
      if (ux !== uy) return ux - uy; // less time left => send first
      return x.msg.createdAt - y.msg.createdAt;
    });
  } else {
    arr.sort((x, y) => x.msg.createdAt - y.msg.createdAt);
  }
  return arr;
}

/**
 * Decide how many copies to give away for one message.
 * Returns 0 = don't send, otherwise the number handed to the peer.
 *
 * Binary Spray-and-Wait: holder keeps floor(c/2), peer receives the rest.
 * At c = 1 the node stops spraying and only delivers to the destination itself.
 */
export function copiesToGive(policy, entry, peerId) {
  if (policy === 'epidemic') return Infinity;
  const c = entry.copies ?? 1;
  if (c <= 1) return entry.msg.dst === peerId ? 1 : 0;
  return Math.floor(c / 2);
}

/** Starting copy budget for a brand-new message. */
export function initialCopies(policy, cfg) {
  if (policy === 'epidemic') return Infinity;
  if (policy === 'spray') return cfg.sprayL;
  return cfg.extemL;
}

/** Human-readable policy names for the report. */
export const POLICY_LABEL = {
  epidemic: 'Epidemic (flood)',
  spray: 'Spray-and-Wait (L=8)',
  extem: 'EXTEM (urgency-first, L=6, duty-cycled)',
};
