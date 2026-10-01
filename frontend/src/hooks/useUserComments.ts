import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { fetchMyCommentVotes } from '../lib/commentApi';
import { castVote, removeVote } from '../lib/feedApi';
import { fetchUserComments } from '../lib/userApi';
import type { UserComment } from '../types/comment';

interface UseUserCommentsResult {
  comments: UserComment[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => void;
  applyVote: (commentId: string, dir: 1 | -1) => void;
}

// A user's "comments" profile tab (F7) — same shape as useUserPosts, voting wired the same way
// usePostDetail's comment-tree voting is (fetchMyCommentVotes + the shared castVote/removeVote helpers).
export function useUserComments(username: string): UseUserCommentsResult {
  const { user } = useAuth();
  const [comments, setComments] = useState<UserComment[]>([]);
  const [after, setAfter] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const requestId = useRef(0);

  const mergeMyVotes = useCallback(
    async (page: UserComment[]): Promise<UserComment[]> => {
      if (!user || page.length === 0) return page;
      try {
        const votes = await fetchMyCommentVotes(page.map((c) => c.id));
        return page.map((c) => (votes[c.id] ? { ...c, myVote: votes[c.id] } : c));
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
    setComments([]);
    setAfter(null);
    setHasMore(true);
    (async () => {
      try {
        const listing = await fetchUserComments(username, null);
        if (id !== requestId.current) return;
        const page = listing.data.children.map((c) => c.data);
        const merged = await mergeMyVotes(page);
        if (id !== requestId.current) return;
        setComments(merged);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id !== requestId.current) return;
        setError('Could not load this user’s comments.');
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
        const listing = await fetchUserComments(username, after);
        if (id !== requestId.current) return;
        const page = listing.data.children.map((c) => c.data);
        const merged = await mergeMyVotes(page);
        if (id !== requestId.current) return;
        setComments((prev) => [...prev, ...merged]);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id === requestId.current) setError('Could not load more comments.');
      } finally {
        if (id === requestId.current) setLoadingMore(false);
      }
    })();
  }, [username, after, hasMore, loading, loadingMore, mergeMyVotes]);

  const applyVote = useCallback((commentId: string, dir: 1 | -1) => {
    let previous: UserComment | undefined;
    setComments((prev) =>
      prev.map((c) => {
        if (c.id !== commentId) return c;
        previous = c;
        const wasVote = c.myVote;
        const removing = wasVote === dir;
        const delta = removing ? -dir : wasVote ? dir * 2 : dir;
        return { ...c, score: c.score + delta, myVote: removing ? undefined : dir };
      }),
    );
    const wasVote = previous?.myVote;
    const action = wasVote === dir ? removeVote('comment', commentId) : castVote('comment', commentId, dir);
    action.catch(() => {
      if (previous) {
        const snapshot = previous;
        setComments((prev) => prev.map((c) => (c.id === commentId ? snapshot : c)));
      }
    });
  }, []);

  return { comments, loading, loadingMore, error, hasMore, loadMore, applyVote };
}
