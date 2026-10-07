// Push alerts to the automat's ntfy.sh topic (free, no account).

export function alert(ctx, topic, message) {
  if (!topic) return;
  ctx.waitUntil(
    fetch(`https://ntfy.sh/${encodeURIComponent(topic)}`, { method: 'POST', body: message }).catch(() => {}),
  );
}

/** Which heartbeat events are worth waking someone up for. */
export function alertForEvent(name, e) {
  switch (e.type) {
    case 'refund':
      return `${name}: refundér ${((e.amountOre || 0) / 100).toFixed(2)} kr – ${e.reason}`;
    case 'fault':
      return `${name}: FEJL ${e.error}`;
    case 'start':
      return e.previousCrash ? `${name}: appen genstartede efter nedbrud` : null;
    case 'configError':
      return `${name}: fejl i config: ${e.error}`;
    case 'command':
      return e.result !== 'ok' ? `${name}: kommando ${e.cmd} fejlede: ${e.result}` : null;
    default:
      return null;
  }
}
