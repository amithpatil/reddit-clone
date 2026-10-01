import { useEffect, useRef } from 'react';
import type { UserComment } from '../types/comment';
import { ProfileCommentRow } from './ProfileCommentRow';
import styles from './ProfileCommentList.module.css';

interface ProfileCommentListProps {
  comments: UserComment[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  hasMore: boolean;
  onLoadMore: () => void;
  onVote: (commentId: string, dir: 1 | -1) => void;
}

// Same IntersectionObserver sentinel pattern as PostList — kept as a separate component rather than a
// generic one since the two render genuinely different item shapes (PostCard vs ProfileCommentRow).
export function ProfileCommentList({
  comments,
  loading,
  loadingMore,
  error,
  hasMore,
  onLoadMore,
  onVote,
}: ProfileCommentListProps) {
  const sentinelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!hasMore || loading) return;
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) onLoadMore();
      },
      { rootMargin: '400px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, loading, onLoadMore]);

  if (loading) {
    return <div className={styles.state}>Loading…</div>;
  }
  if (error && comments.length === 0) {
    return <div className={styles.state}>{error}</div>;
  }
  if (comments.length === 0) {
    return <div className={styles.state}>No comments yet.</div>;
  }

  return (
    <div>
      {comments.map((comment) => (
        <ProfileCommentRow key={comment.id} comment={comment} onVote={onVote} />
      ))}
      {hasMore && <div ref={sentinelRef} className={styles.sentinel} />}
      {loadingMore && <div className={styles.loadingMore}>Loading more…</div>}
    </div>
  );
}
