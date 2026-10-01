import { useCallback, useEffect, useRef, useState } from 'react';
import { browseCommunities, type CommunityBrowseSort } from '../lib/communityApi';
import type { Community } from '../types/community';
import { useCommunityListActions } from './useCommunityListActions';

interface UseCommunityBrowseResult {
  communities: Community[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => void;
  join: (community: Community) => void;
  leave: (community: Community) => void;
  requestJoin: (community: Community) => void;
}

export function useCommunityBrowse(sort: CommunityBrowseSort): UseCommunityBrowseResult {
  const [communities, setCommunities] = useState<Community[]>([]);
  const [after, setAfter] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const requestId = useRef(0);
  const { join, leave, requestJoin } = useCommunityListActions(setCommunities);

  useEffect(() => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    setCommunities([]);
    setAfter(null);
    setHasMore(true);
    (async () => {
      try {
        const listing = await browseCommunities(sort, null);
        if (id !== requestId.current) return;
        setCommunities(listing.data.children.map((c) => c.data));
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id === requestId.current) setError('Could not load communities. Please try again.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [sort]);

  const loadMore = useCallback(() => {
    if (loadingMore || loading || !hasMore || after === null) return;
    const id = requestId.current;
    setLoadingMore(true);
    (async () => {
      try {
        const listing = await browseCommunities(sort, after);
        if (id !== requestId.current) return;
        setCommunities((prev) => [...prev, ...listing.data.children.map((c) => c.data)]);
        setAfter(listing.data.after);
        setHasMore(listing.data.after !== null);
      } catch {
        if (id === requestId.current) setError('Could not load more communities.');
      } finally {
        if (id === requestId.current) setLoadingMore(false);
      }
    })();
  }, [sort, after, hasMore, loading, loadingMore]);

  return { communities, loading, loadingMore, error, hasMore, loadMore, join, leave, requestJoin };
}
