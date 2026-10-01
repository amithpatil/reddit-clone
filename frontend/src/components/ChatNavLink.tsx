import { Link } from 'react-router-dom';
import { useChat } from '../chat/ChatContext';
import styles from './ChatNavLink.module.css';

export function ChatNavLink() {
  const { totalUnread } = useChat();

  return (
    <Link to="/chat" className={styles.link} aria-label="Chat">
      <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
        <path d="M4 4h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-5 4v-4H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z" />
      </svg>
      {totalUnread > 0 && <span className={styles.badge}>{totalUnread > 99 ? '99+' : totalUnread}</span>}
    </Link>
  );
}
