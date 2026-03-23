// static/js/utils/time.js

/**
 * Convert a SQLite CURRENT_TIMESTAMP string ("YYYY-MM-DD HH:MM:SS", local time)
 * to a human-readable relative string like "2h ago", "just now", "3 days ago".
 */
export function timeAgo(sqliteTimestamp) {
  if (!sqliteTimestamp) return 'Unknown';
  // SQLite returns "YYYY-MM-DD HH:MM:SS" with no timezone — treat as local time
  const normalized = sqliteTimestamp.replace(' ', 'T');
  const past = new Date(normalized);
  if (isNaN(past.getTime())) return 'Unknown';

  const diffMs  = Date.now() - past.getTime();
  const diffSec = Math.floor(diffMs / 1000);
  const diffMin = Math.floor(diffSec / 60);
  const diffHr  = Math.floor(diffMin / 60);
  const diffDay = Math.floor(diffHr  / 24);

  if (diffSec < 60)  return 'just now';
  if (diffMin < 60)  return `${diffMin}m ago`;
  if (diffHr  < 24)  return `${diffHr}h ago`;
  if (diffDay === 1) return 'yesterday';
  if (diffDay < 30)  return `${diffDay} days ago`;
  return past.toLocaleDateString('en-PK', {day:'numeric', month:'short'});
}

/**
 * Returns true if the GET /api/sync/status response is the no-sync sentinel
 * {"message": "No sync yet"} rather than a real sync_log row.
 */
export function isNoSync(statusResponse) {
  return statusResponse && 'message' in statusResponse;
}
