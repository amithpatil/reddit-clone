import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchSettings, updateNotificationPrefs } from '../lib/settingsApi';
import { timeAgo } from '../lib/time';
import { useNotifications } from '../notifications/NotificationsContext';
import type { NotificationItem, NotificationType } from '../types/notification';
import styles from './NotificationsInbox.module.css';

const MUTE_TYPES: { type: NotificationType; label: string }[] = [
  { type: 'post_reply', label: 'Post replies' },
  { type: 'reply', label: 'Comment replies' },
  { type: 'mention', label: 'Mentions' },
  { type: 'chat_message', label: 'Chat messages' },
];

// chat_message never links anywhere — chat has no frontend page yet (that's F10) — and reply/mention
// also only link to the post itself, not a specific comment: PostDetail has no comment-anchor/scroll-to
// feature today, same "no link for those today" limitation F8 already accepted for reported comments.
function describe(n: NotificationItem): { text: string; href: string | null } {
  const actor = n.actorUsername ?? '[deleted]';
  const postHref = n.communityName && n.source.postId ? `/r/${n.communityName}/comments/${n.source.postId}` : null;
  switch (n.type) {
    case 'post_reply':
      return { text: `u/${actor} commented on your post "${n.postTitle ?? '[deleted]'}"`, href: postHref };
    case 'reply':
      return { text: `u/${actor} replied to your comment on "${n.postTitle ?? '[deleted]'}"`, href: postHref };
    case 'mention':
      return { text: `u/${actor} mentioned you on "${n.postTitle ?? '[deleted]'}"`, href: postHref };
    case 'chat_message':
      return { text: `u/${actor} sent you a message`, href: null };
    default:
      return { text: 'New notification', href: null };
  }
}

export function NotificationsInbox() {
  const { notifications, unreadCount, loading, markRead, markAllRead } = useNotifications();
  const [prefs, setPrefs] = useState<Partial<Record<NotificationType, boolean>> | null>(null);
  const [prefsError, setPrefsError] = useState<string | null>(null);

  useEffect(() => {
    fetchSettings()
      .then((s) => setPrefs(s.notificationPrefs))
      .catch(() => setPrefsError('Could not load notification settings.'));
  }, []);

  const togglePref = async (type: NotificationType, enabled: boolean) => {
    setPrefs((prev) => ({ ...(prev ?? {}), [type]: enabled }));
    try {
      await updateNotificationPrefs({ [type]: enabled });
    } catch {
      setPrefsError('Could not save that setting.');
    }
  };

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Notifications</h1>
        <button type="button" className={styles.markAllButton} disabled={unreadCount === 0} onClick={() => markAllRead()}>
          Mark all as read
        </button>
      </div>

      <section className={styles.prefs}>
        <h2 className={styles.prefsTitle}>Notify me about</h2>
        {prefsError && <div className={styles.prefsError}>{prefsError}</div>}
        <div className={styles.prefsList}>
          {MUTE_TYPES.map(({ type, label }) => (
            <label key={type} className={styles.prefItem}>
              <input
                type="checkbox"
                checked={prefs ? prefs[type] !== false : true}
                disabled={prefs === null}
                onChange={(e) => togglePref(type, e.target.checked)}
              />
              {label}
            </label>
          ))}
        </div>
      </section>

      {loading && notifications.length === 0 ? (
        <div className={styles.state}>Loading…</div>
      ) : notifications.length === 0 ? (
        <div className={styles.state}>You're all caught up.</div>
      ) : (
        <div className={styles.list}>
          {notifications.map((n) => {
            const { text, href } = describe(n);
            const rowClass = `${styles.row} ${!n.readAt ? styles.unread : ''}`;
            const row = (
              <>
                <p className={styles.text}>{text}</p>
                <span className={styles.time}>{timeAgo(n.createdAt)}</span>
              </>
            );
            return href ? (
              <Link key={n.id} to={href} className={rowClass} onClick={() => markRead(n.id)}>
                {row}
              </Link>
            ) : (
              <div key={n.id} className={rowClass}>
                {row}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
