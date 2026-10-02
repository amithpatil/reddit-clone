import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { fetchFollowStatusBatch, searchUsers, type PublicProfile } from '../lib/userApi';
import { useUserListActions } from './useUserListActions';

interface UseUserSearchResult {
  users: PublicProfile[];
  loading: boolean;
  error: string | null;
  actionError: string | null;
  follow: (profile: PublicProfile) => void;
  unfollow: (profile: PublicProfile) => void;
}

// Not paginated — GET /user/search returns a single capped page server-side, same reasoning as
// useCommunitySearch/usePostSearch and the backend's own search queries.
export function useUserSearch(query: string): UseUserSearchResult {
  const { user: viewer } = useAuth();
  const [users, setUsers] = useState<PublicProfile[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);
  const { follow, unfollow, error: actionError } = useUserListActions(setUsers);

  useEffect(() => {
    if (!query.trim()) {
      setUsers([]);
      setLoading(false);
      setError(null);
      return;
    }
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const results = await searchUsers(query.trim());
        if (id !== requestId.current) return;
        // isFollowing comes back null from GET /user/search itself (auth has no dependency on follow) —
        // resolved in one batched follow-up call, same "one call for the whole page" shape as
        // useUserPosts.mergeMyVotes.
        if (viewer && results.length > 0) {
          try {
            const status = await fetchFollowStatusBatch(results.map((p) => p.username));
            if (id !== requestId.current) return;
            setUsers(results.map((p) => ({ ...p, isFollowing: status[p.username] ?? false })));
            return;
          } catch {
            // Best-effort — the results still render with isFollowing left null.
          }
        }
        setUsers(results);
      } catch {
        if (id === requestId.current) setError('Could not search people. Please try again.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [query, viewer]);

  return { users, loading, error, actionError, follow, unfollow };
}
