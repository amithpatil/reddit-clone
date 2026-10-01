import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { PublicProfile } from '../lib/userApi';
import styles from './FollowButton.module.css';

interface FollowButtonProps {
  profile: PublicProfile;
  isOwnProfile: boolean;
  onFollow: () => void;
  onUnfollow: () => void;
}

// A new component rather than a generalization of JoinButton (community-specific, typed to Community) —
// this codebase's established style is parallel near-duplicate components over a shared generic one (see
// UserResultCard vs CommunityCard).
export function FollowButton({ profile, isOwnProfile, onFollow, onUnfollow }: FollowButtonProps) {
  const { user } = useAuth();

  if (isOwnProfile) {
    return null;
  }

  if (!user) {
    return (
      <Link to="/login" className={`${styles.button} ${styles.follow}`}>
        Log in to Follow
      </Link>
    );
  }

  if (profile.isFollowing) {
    return (
      <button type="button" className={`${styles.button} ${styles.following}`} onClick={onUnfollow}>
        Following
      </button>
    );
  }

  return (
    <button type="button" className={`${styles.button} ${styles.follow}`} onClick={onFollow}>
      Follow
    </button>
  );
}
