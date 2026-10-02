import { useParams } from 'react-router-dom';
import { UserResultCard } from '../components/UserResultCard';
import { useUserConnections, type ConnectionsMode } from '../hooks/useUserConnections';
import listStyles from '../styles/ListPageState.module.css';
import styles from './UserConnections.module.css';

interface UserConnectionsProps {
  mode: ConnectionsMode;
}

export function UserConnections({ mode }: UserConnectionsProps) {
  const { username = '' } = useParams();
  const { profiles, loading, loadingMore, error, actionError, hasMore, loadMore, follow, unfollow } = useUserConnections(username, mode);

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>
        {mode === 'followers' ? `People following u/${username}` : `People u/${username} follows`}
      </h1>

      {actionError && <p className={listStyles.actionError}>{actionError}</p>}

      {loading ? (
        <div className={listStyles.state}>Loading…</div>
      ) : error ? (
        <div className={listStyles.state}>{error}</div>
      ) : profiles.length === 0 ? (
        <div className={listStyles.state}>{mode === 'followers' ? 'No followers yet.' : 'Not following anyone yet.'}</div>
      ) : (
        <div>
          {profiles.map((profile) => (
            <UserResultCard key={profile.id} profile={profile} onFollow={follow} onUnfollow={unfollow} />
          ))}
          {hasMore && (
            <button type="button" className={listStyles.loadMoreButton} disabled={loadingMore} onClick={loadMore}>
              {loadingMore ? 'Loading…' : 'Load more'}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
