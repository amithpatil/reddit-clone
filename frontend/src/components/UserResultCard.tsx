import { Link } from 'react-router-dom';
import type { PublicProfile } from '../lib/userApi';
import styles from './UserResultCard.module.css';

interface UserResultCardProps {
  profile: PublicProfile;
}

export function UserResultCard({ profile }: UserResultCardProps) {
  return (
    <div className={styles.card}>
      <div className={styles.info}>
        <Link className={styles.name} to={`/user/${profile.username}`}>
          u/{profile.username}
        </Link>
        <div className={styles.meta}>{profile.karmaPost + profile.karmaComment} karma</div>
      </div>
    </div>
  );
}
