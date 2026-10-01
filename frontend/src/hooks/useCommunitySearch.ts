import { useEffect, useRef, useState } from 'react';
import { searchCommunities } from '../lib/communityApi';
import type { Community } from '../types/community';
import { useCommunityListActions } from './useCommunityListActions';

interface UseCommunitySearchResult {
  communities: Community[];
  loading: boolean;
  error: string | null;
  join: (community: Community) => void;
  leave: (community: Community) => void;
  requestJoin: (community: Community) => void;
}

// Not paginated — GET /r/search returns a single capped page server-side, same "relevance ranking isn't a
// stable keyset sort key" reasoning already documented on the backend's search queries.
export function useCommunitySearch(query: string): UseCommunitySearchResult {
  const [communities, setCommunities] = useState<Community[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);
  const { join, leave, requestJoin } = useCommunityListActions(setCommunities);

  useEffect(() => {
    if (!query.trim()) {
      setCommunities([]);
      setLoading(false);
      setError(null);
      return;
    }
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const results = await searchCommunities(query.trim());
        if (id !== requestId.current) return;
        setCommunities(results);
      } catch {
        if (id === requestId.current) setError('Could not search communities. Please try again.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [query]);

  return { communities, loading, error, join, leave, requestJoin };
}
