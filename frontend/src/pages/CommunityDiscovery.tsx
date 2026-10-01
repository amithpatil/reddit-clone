import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { CommunityCard } from '../components/CommunityCard';
import { useCommunityBrowse } from '../hooks/useCommunityBrowse';
import { useCommunitySearch } from '../hooks/useCommunitySearch';
import type { CommunityBrowseSort } from '../lib/communityApi';
import styles from './CommunityDiscovery.module.css';

export function CommunityDiscovery() {
  const { user } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const sort = (searchParams.get('sort') as CommunityBrowseSort) || 'popular';
  const urlQuery = searchParams.get('q') ?? '';
  const [inputValue, setInputValue] = useState(urlQuery);
  const [debouncedQuery, setDebouncedQuery] = useState(urlQuery);
  const sentinelRef = useRef<HTMLDivElement>(null);

  // The NavBar's search box navigates here with a new ?q= while this page may already be mounted (same
  // route, just a different query string) — react-router doesn't remount on that, so inputValue's useState
  // initializer wouldn't otherwise pick it up. Sync whenever the URL's own q changes from outside.
  useEffect(() => {
    setInputValue(urlQuery);
    setDebouncedQuery(urlQuery);
  }, [urlQuery]);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(inputValue), 300);
    return () => clearTimeout(timer);
  }, [inputValue]);

  const isSearching = debouncedQuery.trim().length > 0;

  const browse = useCommunityBrowse(sort);
  const search = useCommunitySearch(debouncedQuery);
  const active = isSearching ? search : browse;

  const setSort = (next: CommunityBrowseSort) => {
    const params = new URLSearchParams(searchParams);
    params.set('sort', next);
    setSearchParams(params);
  };

  useEffect(() => {
    if (isSearching || !browse.hasMore || browse.loading) return;
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) browse.loadMore();
      },
      { rootMargin: '400px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isSearching, browse.hasMore, browse.loading]);

  return (
    <div>
      <div className={styles.searchRow}>
        <input
          className={styles.searchInput}
          type="search"
          placeholder="Search communities"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
        />
        {user && (
          <Link to="/communities/create" className={styles.createButton}>
            Create Community
          </Link>
        )}
      </div>

      {!isSearching && (
        <div className={styles.tabs}>
          <button type="button" className={`${styles.tab} ${sort === 'popular' ? styles.tabActive : ''}`} onClick={() => setSort('popular')}>
            Popular
          </button>
          <button type="button" className={`${styles.tab} ${sort === 'new' ? styles.tabActive : ''}`} onClick={() => setSort('new')}>
            New
          </button>
        </div>
      )}

      {active.loading ? (
        <div className={styles.state}>Loading…</div>
      ) : active.error && active.communities.length === 0 ? (
        <div className={styles.state}>{active.error}</div>
      ) : active.communities.length === 0 ? (
        <div className={styles.state}>{isSearching ? 'No communities found.' : 'No communities yet.'}</div>
      ) : (
        <div>
          {active.communities.map((community) => (
            <CommunityCard key={community.id} community={community} onJoin={active.join} onLeave={active.leave} onRequestJoin={active.requestJoin} />
          ))}
          {!isSearching && browse.hasMore && <div ref={sentinelRef} className={styles.sentinel} />}
          {!isSearching && browse.loadingMore && <div className={styles.loadingMore}>Loading more…</div>}
        </div>
      )}
    </div>
  );
}
