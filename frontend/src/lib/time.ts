const UNITS: [string, number][] = [
  ['y', 365 * 24 * 60 * 60],
  ['mo', 30 * 24 * 60 * 60],
  ['d', 24 * 60 * 60],
  ['h', 60 * 60],
  ['m', 60],
];

// "3h ago", "2d ago" — Reddit's own compact style, not "3 hours ago".
export function timeAgo(iso: string): string {
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  for (const [suffix, secondsInUnit] of UNITS) {
    const value = Math.floor(seconds / secondsInUnit);
    if (value >= 1) {
      return `${value}${suffix} ago`;
    }
  }
  return 'just now';
}
