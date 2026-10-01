import { timeAgo } from '../lib/time';
import type { Post } from '../types/post';
import { VoteControl } from './VoteControl';
import styles from './PostCard.module.css';

interface PostCardProps {
  post: Post;
  onVote: (postId: string, dir: 1 | -1) => void;
}

function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

function PostMedia({ post }: { post: Post }) {
  if (post.kind === 'image' || post.kind === 'video') {
    if (post.media?.processingStatus === 'ready' && post.media.thumbnailUrl) {
      return post.kind === 'video' ? (
        <video className={styles.thumbnail} src={post.media.displayUrl ?? undefined} controls />
      ) : (
        <img className={styles.thumbnail} src={post.media.thumbnailUrl} alt="" />
      );
    }
    return <div className={styles.mediaPlaceholder}>{post.kind === 'video' ? 'Video processing…' : 'Image processing…'}</div>;
  }
  if (post.kind === 'link' && post.url) {
    return <div className={styles.domain}>({domainOf(post.url)})</div>;
  }
  if (post.kind === 'text' && post.body) {
    return <p className={styles.snippet}>{post.body}</p>;
  }
  return null;
}

export function PostCard({ post, onVote }: PostCardProps) {
  return (
    <article className={styles.card}>
      <VoteControl score={post.score} myVote={post.myVote} onVote={(dir) => onVote(post.id, dir)} />
      <div className={styles.body}>
        <div className={styles.meta}>
          Posted by u/{post.authorUsername ?? '[deleted]'} in{' '}
          <span className={styles.communityLink}>r/{post.communityName ?? 'unknown'}</span> · {timeAgo(post.createdAt)}
        </div>
        <h2 className={styles.title}>
          {post.title}
          {post.nsfw && <span className={`${styles.badge} ${styles.badgeNsfw}`}>NSFW</span>}
          {post.spoiler && <span className={`${styles.badge} ${styles.badgeSpoiler}`}>Spoiler</span>}
          {post.flair && (
            <span className={styles.flairChip} style={{ backgroundColor: post.flair.color, color: '#fff' }}>
              {post.flair.text}
            </span>
          )}
        </h2>
        <PostMedia post={post} />
        <div className={styles.footer}>{post.commentCount} comments</div>
      </div>
    </article>
  );
}
