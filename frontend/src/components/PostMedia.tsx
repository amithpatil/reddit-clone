import { useState } from 'react';
import { useSettings } from '../settings/SettingsContext';
import type { Post } from '../types/post';
import styles from './PostMedia.module.css';

interface PostMediaProps {
  post: Post;
  // false (default, feed cards): clamps text-post bodies to 3 lines. true (post detail page): shows the
  // complete body.
  fullBody?: boolean;
}

function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

function renderContent(post: Post, fullBody: boolean) {
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
    const snippetClass = fullBody ? styles.snippet : `${styles.snippet} ${styles.snippetTruncated}`;
    return <p className={snippetClass}>{post.body}</p>;
  }
  return null;
}

export function PostMedia({ post, fullBody = false }: PostMediaProps) {
  const { nsfwBlurEffective } = useSettings();
  const [revealed, setRevealed] = useState(false);
  const content = renderContent(post, fullBody);
  if (content === null) return null;

  if (post.nsfw && nsfwBlurEffective && !revealed) {
    return (
      <div className={styles.nsfwWrapper}>
        <div className={styles.nsfwBlurred}>{content}</div>
        <button type="button" className={styles.nsfwReveal} onClick={() => setRevealed(true)}>
          NSFW — click to view
        </button>
      </div>
    );
  }
  return content;
}
