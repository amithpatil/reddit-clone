import { useJoinRequests } from '../hooks/useJoinRequests';
import { timeAgo } from '../lib/time';
import styles from './JoinRequestsTab.module.css';

interface JoinRequestsTabProps {
  communityName: string;
}

export function JoinRequestsTab({ communityName }: JoinRequestsTabProps) {
  const { requests, loading, error, approve, deny } = useJoinRequests(communityName);

  if (loading) return <div className={styles.state}>Loading…</div>;
  if (error) return <div className={styles.state}>{error}</div>;
  if (requests.length === 0) return <div className={styles.state}>No pending join requests.</div>;

  return (
    <div>
      {requests.map((r) => (
        <div key={r.userId} className={styles.row}>
          <div>
            <strong>u/{r.username ?? '[deleted]'}</strong>
            <div className={styles.meta}>requested {timeAgo(r.requestedAt)}</div>
          </div>
          <div className={styles.actions}>
            <button type="button" className={styles.approveButton} onClick={() => approve(r.userId)}>
              Approve
            </button>
            <button type="button" className={styles.denyButton} onClick={() => deny(r.userId)}>
              Deny
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}
