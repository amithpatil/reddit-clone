import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CommunityCard } from '../components/CommunityCard';
import { PostCard } from '../components/PostCard';
import { UserResultCard } from '../components/UserResultCard';
import { useCommunitySearch } from '../hooks/useCommunitySearch';
import { usePostSearch } from '../hooks/usePostSearch';
import { useUserSearch } from '../hooks/useUserSearch';
import styles from './Search.module.css';

export function Search() {
  const [searchParams] = useSearchParams();
  const urlQuery = searchParams.get('q') ?? '';
  const [inputValue, setInputValue] = useState(urlQuery);
  const [debouncedQuery, setDebouncedQuery] = useState(urlQuery);

  // Same "resync when the URL's own q changes from outside" need as CommunityDiscovery — the nav bar's
  // search box navigates here with a new ?q= while this page may already be mounted.
  useEffect(() => {
    setInputValue(urlQuery);
    setDebouncedQuery(urlQuery);
  }, [urlQuery]);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(inputValue), 300);
    return () => clearTimeout(timer);
  }, [inputValue]);

  const query = debouncedQuery.trim();
  const posts = usePostSearch(query);
  const communities = useCommunitySearch(query);
  const users = useUserSearch(query);

  return (
    <div>
      <input
        className={styles.searchInput}
        type="search"
        placeholder="Search reddit"
        value={inputValue}
        onChange={(e) => setInputValue(e.target.value)}
        autoFocus
      />

      {!query ? (
        <div className={styles.state}>Type something to search.</div>
      ) : (
        <>
          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Posts</h2>
            {posts.loading ? (
              <div className={styles.state}>Loading…</div>
            ) : posts.error ? (
              <div className={styles.state}>{posts.error}</div>
            ) : posts.posts.length === 0 ? (
              <div className={styles.state}>No posts found for "{query}".</div>
            ) : (
              posts.posts.map((post) => <PostCard key={post.id} post={post} onVote={posts.applyVote} />)
            )}
          </section>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>Communities</h2>
            {communities.loading ? (
              <div className={styles.state}>Loading…</div>
            ) : communities.error ? (
              <div className={styles.state}>{communities.error}</div>
            ) : communities.communities.length === 0 ? (
              <div className={styles.state}>No communities found for "{query}".</div>
            ) : (
              communities.communities.map((community) => (
                <CommunityCard
                  key={community.id}
                  community={community}
                  onJoin={communities.join}
                  onLeave={communities.leave}
                  onRequestJoin={communities.requestJoin}
                />
              ))
            )}
          </section>

          <section className={styles.section}>
            <h2 className={styles.sectionTitle}>People</h2>
            {users.loading ? (
              <div className={styles.state}>Loading…</div>
            ) : users.error ? (
              <div className={styles.state}>{users.error}</div>
            ) : users.users.length === 0 ? (
              <div className={styles.state}>No people found for "{query}".</div>
            ) : (
              users.users.map((profile) => (
                <UserResultCard key={profile.id} profile={profile} onFollow={users.follow} onUnfollow={users.unfollow} />
              ))
            )}
          </section>
        </>
      )}
    </div>
  );
}
