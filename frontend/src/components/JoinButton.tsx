import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { Community } from '../types/community';
import styles from './JoinButton.module.css';

interface JoinButtonProps {
  community: Community;
  onJoin: () => void;
  onLeave: () => void;
  onRequestJoin: () => void;
}

export function JoinButton({ community, onJoin, onLeave, onRequestJoin }: JoinButtonProps) {
  const { user } = useAuth();

  if (!user) {
    return (
      <Link to="/login" className={`${styles.button} ${styles.join}`}>
        Log in to Join
      </Link>
    );
  }

  if (community.isMember) {
    return (
      <button type="button" className={`${styles.button} ${styles.joined}`} onClick={onLeave}>
        Joined
      </button>
    );
  }

  // public and restricted: joining is unrestricted (restricted only gates posting, handled separately in
  // F6's submit-post flow — not this page's job).
  if (community.type !== 'private') {
    return (
      <button type="button" className={`${styles.button} ${styles.join}`} onClick={onJoin}>
        Join
      </button>
    );
  }

  if (community.joinRequestStatus === 'pending') {
    return (
      <button type="button" className={`${styles.button} ${styles.pending}`} disabled>
        Request Pending
      </button>
    );
  }

  return (
    <button type="button" className={`${styles.button} ${styles.join}`} onClick={onRequestJoin}>
      Request to Join
    </button>
  );
}
