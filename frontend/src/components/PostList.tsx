import { useEffect, useRef } from 'react';
import type { Post } from '../types/post';
import { PostCard } from './PostCard';
import styles from './PostList.module.css';

interface PostListProps {
  posts: Post[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  hasMore: boolean;
  onLoadMore: () => void;
  onVote: (postId: string, dir: 1 | -1) => void;
}

export function PostList({ posts, loading, loadingMore, error, hasMore, onLoadMore, onVote }: PostListProps) {
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
  if (error && posts.length === 0) {
    return <div className={styles.state}>{error}</div>;
  }
  if (posts.length === 0) {
    return <div className={styles.state}>No posts yet.</div>;
  }

  return (
    <div>
      {posts.map((post) => (
        <PostCard key={post.id} post={post} onVote={onVote} />
      ))}
      {hasMore && <div ref={sentinelRef} className={styles.sentinel} />}
      {loadingMore && <div className={styles.loadingMore}>Loading more…</div>}
    </div>
  );
}
