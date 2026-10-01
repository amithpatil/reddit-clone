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

export function UserResultCard({ profile, onFollow, onUnfollow }: UserResultCardProps) {
  const { user: viewer } = useAuth();

  return (
    <div className={styles.card}>
      <div className={styles.info}>
        <Link className={styles.name} to={`/user/${profile.username}`}>
          u/{profile.username}
        </Link>
        <div className={styles.meta}>{profile.karmaPost + profile.karmaComment} karma</div>
      </div>
      <FollowButton
        profile={profile}
        isOwnProfile={viewer?.username === profile.username}
        onFollow={() => onFollow(profile)}
        onUnfollow={() => onUnfollow(profile)}
      />
    </div>
  );
}
