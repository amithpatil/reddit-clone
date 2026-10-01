import { Link } from 'react-router-dom';
import type { Community } from '../types/community';
import { JoinButton } from './JoinButton';
import styles from './CommunityCard.module.css';

interface CommunityCardProps {
  community: Community;
  onJoin: (community: Community) => void;
  onLeave: (community: Community) => void;
  onRequestJoin: (community: Community) => void;
}

export function CommunityCard({ community, onJoin, onLeave, onRequestJoin }: CommunityCardProps) {
  return (
    <div className={styles.card}>
      <div className={styles.info}>
        <Link className={styles.name} to={`/r/${community.name}`}>
          r/{community.name}
        </Link>
        <div className={styles.meta}>{community.subscriberCount} members</div>
        {community.description && <p className={styles.description}>{community.description}</p>}
      </div>
      <JoinButton
        community={community}
        onJoin={() => onJoin(community)}
        onLeave={() => onLeave(community)}
        onRequestJoin={() => onRequestJoin(community)}
      />
    </div>
  );
}
