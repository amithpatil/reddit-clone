import { useEffect, useRef, useState } from 'react';
import { searchUsers, type PublicProfile } from '../lib/userApi';

interface UseUserSearchResult {
  users: PublicProfile[];
  loading: boolean;
  error: string | null;
}

// Not paginated — GET /user/search returns a single capped page server-side, same reasoning as
// useCommunitySearch/usePostSearch and the backend's own search queries.
export function useUserSearch(query: string): UseUserSearchResult {
  const [users, setUsers] = useState<PublicProfile[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);

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
        setUsers(results);
      } catch {
        if (id === requestId.current) setError('Could not search people. Please try again.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [query]);

  return { users, loading, error };
}
