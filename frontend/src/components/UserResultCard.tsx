import { memo } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { PublicProfile } from '../lib/userApi';
import { FollowButton } from './FollowButton';
import styles from './UserResultCard.module.css';

interface UserResultCardProps {
  profile: PublicProfile;
  onFollow: (profile: PublicProfile) => void;
  onUnfollow: (profile: PublicProfile) => void;
}

// Memoized: useUserListActions's optimistic update deliberately preserves object identity for every row
// it doesn't touch, so without this every row in a long followers/following/search list would re-render on
// any single follow/unfollow click instead of just the one that changed.
export const UserResultCard = memo(function UserResultCard({ profile, onFollow, onUnfollow }: UserResultCardProps) {
  const { user: viewer } = useAuth();
  // AuthService.deleteAccount anonymizes username to "deleted_<id>" but never removes the row — show the
  // app's usual '[deleted]' placeholder instead of a clickable link/Follow button for a ghost account,
  // matching PostCard/CommentThread/NotificationsInbox's existing convention for a missing/anonymized actor.
  const deleted = profile.status === 'deleted';

  return (
    <div className={styles.card}>
      <div className={styles.info}>
        {deleted ? (
          <span className={styles.name}>[deleted]</span>
        ) : (
          <Link className={styles.name} to={`/user/${profile.username}`}>
            u/{profile.username}
          </Link>
        )}
        <div className={styles.meta}>{profile.karmaPost + profile.karmaComment} karma</div>
      </div>
      {!deleted && (
        <FollowButton
          profile={profile}
          isOwnProfile={viewer?.username === profile.username}
          onFollow={() => onFollow(profile)}
          onUnfollow={() => onUnfollow(profile)}
        />
      )}
    </div>
  );
});
