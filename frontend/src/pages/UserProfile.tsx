import { useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { PostCard } from '../components/PostCard';
import { PostList } from '../components/PostList';
import { ProfileCommentList } from '../components/ProfileCommentList';
import { ProfileCommentRow } from '../components/ProfileCommentRow';
import { useUserComments } from '../hooks/useUserComments';
import { useUserPosts } from '../hooks/useUserPosts';
import { ApiError } from '../lib/apiClient';
import { fetchPublicProfile, type PublicProfile } from '../lib/userApi';
import styles from './UserProfile.module.css';

type Tab = 'overview' | 'posts' | 'comments';

const JOIN_DATE_FORMAT = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' });

export function UserProfile() {
  const { username = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = (searchParams.get('tab') as Tab) || 'overview';

  const [profile, setProfile] = useState<PublicProfile | null>(null);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [profileLoading, setProfileLoading] = useState(true);

  // Both tabs' data load unconditionally on mount, not lazily per-tab — switching tabs is then instant
  // with no refetch, acceptable here since a profile page isn't a hot path the way the home feed is.
  const posts = useUserPosts(username);
  const comments = useUserComments(username);

  useEffect(() => {
    let cancelled = false;
    setProfileLoading(true);
    setProfileError(null);
    (async () => {
      try {
        const p = await fetchPublicProfile(username);
        if (!cancelled) setProfile(p);
      } catch (err) {
        if (cancelled) return;
        setProfileError(err instanceof ApiError && err.status === 404 ? 'No such user.' : 'Could not load this profile.');
      } finally {
        if (!cancelled) setProfileLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [username]);

  const setTab = (next: Tab) => {
    const params = new URLSearchParams(searchParams);
    if (next === 'overview') {
      params.delete('tab');
    } else {
      params.set('tab', next);
    }
    setSearchParams(params);
  };

  // Client-side interleave of two independently-paginated sources, sorted by recency — no dedicated
  // backend union endpoint needed, since both /submitted and /comments already paginate on their own.
  const overviewItems = useMemo(() => {
    type Item = { createdAt: string } & ({ kind: 'post'; post: (typeof posts.posts)[number] } | { kind: 'comment'; comment: (typeof comments.comments)[number] });
    const items: Item[] = [
      ...posts.posts.map((post) => ({ kind: 'post' as const, post, createdAt: post.createdAt })),
      ...comments.comments.map((comment) => ({ kind: 'comment' as const, comment, createdAt: comment.createdAt })),
    ];
    return items.sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0));
  }, [posts.posts, comments.comments]);

  if (profileLoading) {
    return <div className={styles.state}>Loading…</div>;
  }
  if (profileError || !profile) {
    return <div className={styles.state}>{profileError ?? 'No such user.'}</div>;
  }

  const suspended = profile.status !== 'active';

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div className={styles.avatar}>{profile.username.slice(0, 1).toUpperCase()}</div>
        <div>
          <h1 className={styles.username}>u/{profile.username}</h1>
          <div className={styles.joined}>Joined {JOIN_DATE_FORMAT.format(new Date(profile.createdAt))}</div>
        </div>
        <div className={styles.karma}>
          <div className={styles.karmaStat}>
            <span className={styles.karmaValue}>{profile.karmaPost}</span>
            <span className={styles.karmaLabel}>Post Karma</span>
          </div>
          <div className={styles.karmaStat}>
            <span className={styles.karmaValue}>{profile.karmaComment}</span>
            <span className={styles.karmaLabel}>Comment Karma</span>
          </div>
        </div>
      </div>

      {suspended && (
        <div className={styles.banner}>
          {profile.status === 'banned' ? 'This account has been suspended.' : 'This account has been deleted.'}
        </div>
      )}

      <div className={styles.tabs}>
        {(['overview', 'posts', 'comments'] as Tab[]).map((t) => (
          <button
            key={t}
            type="button"
            className={`${styles.tab} ${tab === t ? styles.tabActive : ''}`}
            onClick={() => setTab(t)}
          >
            {t === 'overview' ? 'Overview' : t === 'posts' ? 'Posts' : 'Comments'}
          </button>
        ))}
      </div>

      {tab === 'overview' && (
        <>
          {posts.loading || comments.loading ? (
            <div className={styles.state}>Loading…</div>
          ) : overviewItems.length === 0 ? (
            <div className={styles.state}>Nothing here yet.</div>
          ) : (
            <div>
              {overviewItems.map((item) =>
                item.kind === 'post' ? (
                  <PostCard key={`post-${item.post.id}`} post={item.post} onVote={posts.applyVote} />
                ) : (
                  <ProfileCommentRow key={`comment-${item.comment.id}`} comment={item.comment} onVote={comments.applyVote} />
                ),
              )}
              {(posts.hasMore || comments.hasMore) && (
                <button
                  type="button"
                  className={styles.loadMoreButton}
                  disabled={posts.loadingMore || comments.loadingMore}
                  onClick={() => {
                    if (posts.hasMore) posts.loadMore();
                    if (comments.hasMore) comments.loadMore();
                  }}
                >
                  {posts.loadingMore || comments.loadingMore ? 'Loading…' : 'Load more'}
                </button>
              )}
            </div>
          )}
        </>
      )}

      {tab === 'posts' && (
        <PostList
          posts={posts.posts}
          loading={posts.loading}
          loadingMore={posts.loadingMore}
          error={posts.error}
          hasMore={posts.hasMore}
          onLoadMore={posts.loadMore}
          onVote={posts.applyVote}
        />
      )}

      {tab === 'comments' && (
        <ProfileCommentList
          comments={comments.comments}
          loading={comments.loading}
          loadingMore={comments.loadingMore}
          error={comments.error}
          hasMore={comments.hasMore}
          onLoadMore={comments.loadMore}
          onVote={comments.applyVote}
        />
      )}
    </div>
  );
}
