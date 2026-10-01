import styles from './Logo.module.css';

// Original mark: an orange rounded tile with a white upvote triangle. Deliberately NOT a
// reproduction of Reddit's trademarked Snoo mascot/wordmark artwork — evocative of the brand
// (upvote arrows are core to the product itself, not just its logo) without copying the
// protected asset. See lets-start-with-phase-jolly-anchor.md plan for the reasoning.
export function Logo({ withWordmark = true, size = 32 }: { withWordmark?: boolean; size?: number }) {
  return (
    <span className={styles.logo}>
      <svg
        className={styles.mark}
        width={size}
        height={size}
        viewBox="0 0 32 32"
        role="img"
        aria-label="App logo"
      >
        <rect width="32" height="32" rx="16" fill="var(--color-primary)" />
        <path d="M16 8 L23.5 20.5 H8.5 Z" fill="#ffffff" />
      </svg>
      {withWordmark && <span className={styles.wordmark}>reddit</span>}
    </span>
  );
}
