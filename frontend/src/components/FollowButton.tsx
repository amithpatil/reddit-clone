import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { PublicProfile } from '../lib/userApi';
import pillStyles from '../styles/PillButton.module.css';
import styles from './FollowButton.module.css';

interface FollowButtonProps {
  profile: PublicProfile;
  isOwnProfile: boolean;
  onFollow: () => void;
  onUnfollow: () => void;
}

// A new component rather than a generalization of JoinButton (community-specific, typed to Community) —
// this codebase's established style is parallel near-duplicate components over a shared generic one (see
// UserResultCard vs CommunityCard). The two share only their purely-visual pill-button base, factored into
// styles/PillButton.module.css.
export function FollowButton({ profile, isOwnProfile, onFollow, onUnfollow }: FollowButtonProps) {
  const { user } = useAuth();

  if (isOwnProfile) {
    return null;
  }

  if (!user) {
    return (
      <Link to="/login" className={`${pillStyles.button} ${styles.follow}`}>
        Log in to Follow
      </Link>
    );
  }

  // isFollowing is null when it couldn't be resolved (a best-effort status fetch failed) — distinct from a
  // confirmed false. Rendering a disabled, visually-neutral state here avoids showing an actionable "Follow"
  // button that might actually already be followed, which would otherwise silently no-op on click with no
  // indication the displayed state was never confirmed.
  if (profile.isFollowing === null) {
    return (
      <button type="button" className={`${pillStyles.button} ${styles.unknown}`} disabled>
        Follow
      </button>
    );
  }

  if (profile.isFollowing) {
    return (
      <button type="button" className={`${pillStyles.button} ${styles.following}`} onClick={onUnfollow}>
        Following
      </button>
    );
  }

  return (
    <button type="button" className={`${pillStyles.button} ${styles.follow}`} onClick={onFollow}>
      Follow
    </button>
  );
}
