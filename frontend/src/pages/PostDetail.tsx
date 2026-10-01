import { Link, useParams, useSearchParams } from 'react-router-dom';
import { CommentSortDropdown } from '../components/CommentSortDropdown';
import { CommentThread } from '../components/CommentThread';
import { PostMedia } from '../components/PostMedia';
import { ReplyBox } from '../components/ReplyBox';
import { VoteControl } from '../components/VoteControl';
import { usePostDetail } from '../hooks/usePostDetail';
import { timeAgo } from '../lib/time';
import type { CommentSortType } from '../types/comment';
import styles from './PostDetail.module.css';

export function PostDetail() {
  const { communityName = '', postId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const sort = (searchParams.get('commentSort') as CommentSortType) || 'best';

  const { post, comments, loading, error, applyPostVote, applyCommentVote, submitComment } = usePostDetail(
    communityName,
    postId,
    sort,
  );

  if (loading) {
    return <div className={styles.state}>Loading…</div>;
  }
  if (error || !post) {
    return <div className={styles.state}>{error ?? 'Post not found.'}</div>;
  }

  return (
    <div>
      <article className={styles.header}>
        <VoteControl score={post.score} myVote={post.myVote} onVote={applyPostVote} />
        <div className={styles.body}>
          <div className={styles.meta}>
            Posted by u/{post.authorUsername ?? '[deleted]'} in{' '}
            <Link className={styles.communityLink} to={`/r/${post.communityName}`}>
              r/{post.communityName ?? 'unknown'}
            </Link>{' '}
            · {timeAgo(post.createdAt)}
          </div>
          <h1 className={styles.title}>
            {post.title}
            {post.nsfw && <span className={`${styles.badge} ${styles.badgeNsfw}`}>NSFW</span>}
            {post.spoiler && <span className={`${styles.badge} ${styles.badgeSpoiler}`}>Spoiler</span>}
          </h1>
          <PostMedia post={post} fullBody />
          <div className={styles.footer}>{post.commentCount} comments</div>
        </div>
      </article>

      <div className={styles.commentsSection}>
        <CommentSortDropdown />
        <ReplyBox onSubmit={(body) => submitComment(null, body)} />
        {comments.length === 0 ? (
          <p>No comments yet. Be the first to share what you think!</p>
        ) : (
          comments.map((c) => <CommentThread key={c.id} comment={c} onVote={applyCommentVote} onReply={submitComment} />)
        )}
      </div>
    </div>
  );
}
