import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import { castVote, fetchFeedPage, fetchMyPostVotes, removeVote } from '../lib/feedApi';
import type { Post, SortType, TopPeriod } from '../types/post';

interface UseFeedResult {
  posts: Post[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  // True when the feed 403'd (a private community the viewer can't view) — distinct from `error`, a
  // community page uses this to show an access message instead of a generic failure. `communityName` has
  // been a generic parameter since F2 (home passes "all", which never 403s); F4 is its first caller with a
  // real community name, where this distinction actually matters.
  forbidden: boolean;
  hasMore: boolean;
  loadMore: () => void;
  applyVote: (postId: string, dir: 1 | -1) => void;
}

export function useFeed(communityName: string, sort: SortType, period: TopPeriod): UseFeedResult {
  const { user } = useAuth();
  const [posts, setPosts] = useState<Post[]>([]);
  const [after, setAfter] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  const requestId = useRef(0);

  const mergeMyVotes = useCallback(
    async (page: Post[]): Promise<Post[]> => {
      if (!user || page.length === 0) return page;
      try {
        const votes = await fetchMyPostVotes(page.map((p) => p.id));
        return page.map((p) => (votes[p.id] ? { ...p, myVote: votes[p.id] } : p));
      } catch {
        // Vote-state is a nice-to-have overlay, not core to showing the feed — a failure here shouldn't
        // block the feed from rendering.
        return page;
      }
    },
    [user],
  );

  // Reset and refetch page 1 whenever the feed identity (community/sort/period) or login state changes —
  // login state matters because myVote and hidden-item filtering are viewer-specific.
  useEffect(() => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    setForbidden(false);
    setPosts([]);
    setAfter(null);
    setHasMore(true);
    (async () => {
      try {
        const listing = await fetchFeedPage(communityName, sort, null, period);
        if (id !== requestId.current) return;
        const page = listing.data.children.map((c) => c.data);
        const merged = await mergeMyVotes(page);
        if (id !== requestId.current) return;
        setPosts(merged);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch (err) {
        if (id !== requestId.current) return;
        if (err instanceof ApiError && err.status === 403) {
          setForbidden(true);
        } else {
          setError('Could not load the feed. Please try again.');
        }
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [communityName, sort, period, mergeMyVotes]);

  const loadMore = useCallback(() => {
    if (loadingMore || loading || !hasMore || after === null) return;
    const id = requestId.current;
    setLoadingMore(true);
    (async () => {
      try {
        const listing = await fetchFeedPage(communityName, sort, after, period);
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
  }, [communityName, sort, period, after, hasMore, loading, loadingMore, mergeMyVotes]);

  // Optimistic: update score/myVote immediately, fire the request, and roll back on failure. Matches the
  // backend's own async vote model (POST /api/vote returns void — the outbox worker applies the real
  // score change later), so the UI doesn't wait on it. Clicking the already-active direction un-votes;
  // clicking the other direction swings the score by 2 (one vote removed, the opposite one added).
  const applyVote = useCallback(
    (postId: string, dir: 1 | -1) => {
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
      const action =
        wasVote === dir ? removeVote('post', postId) : castVote('post', postId, dir);
      action.catch(() => {
        if (previous) {
          const snapshot = previous;
          setPosts((prev) => prev.map((p) => (p.id === postId ? snapshot : p)));
        }
      });
    },
    [],
  );

  return { posts, loading, loadingMore, error, forbidden, hasMore, loadMore, applyVote };
}
