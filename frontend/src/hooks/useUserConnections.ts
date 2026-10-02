import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchFollowers, fetchFollowing, type PublicProfile } from '../lib/userApi';
import { useUserListActions } from './useUserListActions';

export type ConnectionsMode = 'followers' | 'following';

interface UseUserConnectionsResult {
  profiles: PublicProfile[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  actionError: string | null;
  hasMore: boolean;
  loadMore: () => void;
  follow: (profile: PublicProfile) => void;
  unfollow: (profile: PublicProfile) => void;
}

// Followers/following list for a profile — same cursor-pagination shape as useUserPosts, just backed by
// GET /user/{username}/followers or /following (both already attach a real isFollowing for the calling
// viewer server-side, unlike /about or /search, since these two endpoints are entirely owned by the follow
// module — no extra follow-status call needed here).
export function useUserConnections(username: string, mode: ConnectionsMode): UseUserConnectionsResult {
  const [profiles, setProfiles] = useState<PublicProfile[]>([]);
  const [after, setAfter] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const requestId = useRef(0);
  const { follow, unfollow, error: actionError } = useUserListActions(setProfiles);

  const fetchPage = mode === 'followers' ? fetchFollowers : fetchFollowing;

  useEffect(() => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    setProfiles([]);
    setAfter(null);
    setHasMore(true);
    (async () => {
      try {
        const listing = await fetchPage(username, null);
        if (id !== requestId.current) return;
        setProfiles(listing.data.children.map((c) => c.data));
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id !== requestId.current) return;
        setError(mode === 'followers' ? 'Could not load followers.' : 'Could not load who this user follows.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [username, mode, fetchPage]);

  const loadMore = useCallback(() => {
    if (loadingMore || loading || !hasMore || after === null) return;
    const id = requestId.current;
    setLoadingMore(true);
    (async () => {
      try {
        const listing = await fetchPage(username, after);
        if (id !== requestId.current) return;
        setProfiles((prev) => [...prev, ...listing.data.children.map((c) => c.data)]);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id === requestId.current) setError('Could not load more.');
      } finally {
        if (id === requestId.current) setLoadingMore(false);
      }
    })();
  }, [username, after, hasMore, loading, loadingMore, fetchPage]);

  return { profiles, loading, loadingMore, error, actionError, hasMore, loadMore, follow, unfollow };
}
