import { Link } from 'react-router-dom';
import { timeAgo } from '../lib/time';
import type { Post } from '../types/post';
import { PostMedia } from './PostMedia';
import { VoteControl } from './VoteControl';
import styles from './PostCard.module.css';

interface PostCardProps {
  post: Post;
  onVote: (postId: string, dir: 1 | -1) => void;
}

export function PostCard({ post, onVote }: PostCardProps) {
  const detailHref = `/r/${post.communityName ?? 'all'}/comments/${post.id}`;

  return (
    <article className={styles.card}>
      <VoteControl score={post.score} myVote={post.myVote} onVote={(dir) => onVote(post.id, dir)} />
      <div className={styles.body}>
        <div className={styles.meta}>
          Posted by{' '}
          {post.authorUsername ? (
            <Link className={styles.authorLink} to={`/user/${post.authorUsername}`}>
              u/{post.authorUsername}
            </Link>
          ) : (
            'u/[deleted]'
          )}{' '}
          in{' '}
          <Link className={styles.communityLink} to={`/r/${post.communityName}`}>
            r/{post.communityName ?? 'unknown'}
          </Link>{' '}
          · {timeAgo(post.createdAt)}
        </div>
        <h2 className={styles.title}>
          {post.pinned && <span className={`${styles.badge} ${styles.badgePinned}`}>📌 Pinned</span>}
          <Link className={styles.titleLink} to={detailHref}>
            {post.title}
          </Link>
          {post.nsfw && <span className={`${styles.badge} ${styles.badgeNsfw}`}>NSFW</span>}
          {post.spoiler && <span className={`${styles.badge} ${styles.badgeSpoiler}`}>Spoiler</span>}
          {post.flair && (
            <span className={styles.flairChip} style={{ backgroundColor: post.flair.color, color: '#fff' }}>
              {post.flair.text}
            </span>
          )}
        </h2>
        <PostMedia post={post} />
        <Link className={styles.footer} to={detailHref}>
          {post.commentCount} comments
        </Link>
      </div>
    </article>
  );
}
