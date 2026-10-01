import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { castVote, fetchMyPostVotes, removeVote } from '../lib/feedApi';
import { fetchUserPosts } from '../lib/userApi';
import type { Post } from '../types/post';

interface UseUserPostsResult {
  posts: Post[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => void;
  applyVote: (postId: string, dir: 1 | -1) => void;
}

// A user's "submitted" profile tab (F7) — same shape as useFeed, trimmed of the sort/period params that
// don't apply to a single user's own posts (always newest-first).
export function useUserPosts(username: string): UseUserPostsResult {
  const { user } = useAuth();
  const [posts, setPosts] = useState<Post[]>([]);
  const [after, setAfter] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const requestId = useRef(0);

  const mergeMyVotes = useCallback(
    async (page: Post[]): Promise<Post[]> => {
      if (!user || page.length === 0) return page;
      try {
        const votes = await fetchMyPostVotes(page.map((p) => p.id));
        return page.map((p) => (votes[p.id] ? { ...p, myVote: votes[p.id] } : p));
      } catch {
        return page;
      }
    },
    [user],
  );

  useEffect(() => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    setPosts([]);
    setAfter(null);
    setHasMore(true);
    (async () => {
      try {
        const listing = await fetchUserPosts(username, null);
        if (id !== requestId.current) return;
        const page = listing.data.children.map((c) => c.data);
        const merged = await mergeMyVotes(page);
        if (id !== requestId.current) return;
        setPosts(merged);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id !== requestId.current) return;
        setError('Could not load this user’s posts.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [username, mergeMyVotes]);

  const loadMore = useCallback(() => {
    if (loadingMore || loading || !hasMore || after === null) return;
    const id = requestId.current;
    setLoadingMore(true);
    (async () => {
      try {
        const listing = await fetchUserPosts(username, after);
        if (id !== requestId.current) return;
        const page = listing.data.children.map((c) => c.data);
        const merged = await mergeMyVotes(page);
        if (id !== requestId.current) return;
        setPosts((prev) => [...prev, ...merged]);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id === requestId.current) setError('Could not load more posts.');
      } finally {
        if (id === requestId.current) setLoadingMore(false);
      }
    })();
  }, [username, after, hasMore, loading, loadingMore, mergeMyVotes]);

  // Same optimistic toggle/swing logic as useFeed.applyVote.
  const applyVote = useCallback((postId: string, dir: 1 | -1) => {
    let previous: Post | undefined;
    setPosts((prev) =>
      prev.map((p) => {
        if (p.id !== postId) return p;
        previous = p;
        const wasVote = p.myVote;
        const removing = wasVote === dir;
        const delta = removing ? -dir : wasVote ? dir * 2 : dir;
        return { ...p, score: p.score + delta, myVote: removing ? undefined : dir };
      }),
    );
    const wasVote = previous?.myVote;
    const action = wasVote === dir ? removeVote('post', postId) : castVote('post', postId, dir);
    action.catch(() => {
      if (previous) {
        const snapshot = previous;
        setPosts((prev) => prev.map((p) => (p.id === postId ? snapshot : p)));
      }
    });
  }, []);

  return { posts, loading, loadingMore, error, hasMore, loadMore, applyVote };
}
