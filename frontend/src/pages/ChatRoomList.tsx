import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useChat } from '../chat/ChatContext';
import { ApiError } from '../lib/apiClient';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import styles from './ChatRoomList.module.css';

export function ChatRoomList() {
  const { user } = useAuth();
  const { rooms, loadingRooms, startRoom } = useChat();
  const navigate = useNavigate();
  const [usernamesInput, setUsernamesInput] = useState('');
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleStart = async (e: FormEvent) => {
    e.preventDefault();
    const usernames = usernamesInput
      .split(',')
      .map((u) => u.trim())
      .filter(Boolean);
    if (usernames.length === 0) return;
    setStarting(true);
    setError(null);
    try {
      const roomId = await startRoom(usernames);
      setUsernamesInput('');
      navigate(`/chat/${roomId}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setError('No such user — check the username(s) and try again.');
      } else if (err instanceof ApiError && err.status === 400) {
        setError('A chat needs at least one other person.');
      } else {
        setError('Could not start that chat.');
      }
    } finally {
      setStarting(false);
    }
  };

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Chat</h1>

      <form className={styles.startForm} onSubmit={handleStart}>
        <input
          className={styles.startInput}
          type="text"
          placeholder="Username(s), comma-separated"
          value={usernamesInput}
          onChange={(e) => setUsernamesInput(e.target.value)}
        />
        <button type="submit" className={styles.startButton} disabled={starting || !usernamesInput.trim()}>
          Start
        </button>
      </form>
      {error && <div className={styles.error}>{error}</div>}

      {loadingRooms && rooms.length === 0 ? (
        <div className={styles.state}>Loading…</div>
      ) : rooms.length === 0 ? (
        <div className={styles.state}>No conversations yet.</div>
      ) : (
        <div className={styles.list}>
          {rooms.map((r) => {
            const lastMessage = decodeHtmlEntities(r.lastMessageBody);
            const preview =
              lastMessage === null
                ? ''
                : r.lastMessageSenderId === user?.id
                  ? `You: ${lastMessage}`
                  : lastMessage;
            return (
              <Link key={r.roomId} to={`/chat/${r.roomId}`} className={`${styles.row} ${r.unreadCount > 0 ? styles.unread : ''}`}>
                <div className={styles.rowMain}>
                  <span className={styles.participants}>{r.otherParticipants.join(', ') || '(empty room)'}</span>
                  {preview && <p className={styles.preview}>{preview}</p>}
                </div>
                <div className={styles.rowMeta}>
                  {r.lastMessageAt && <span className={styles.time}>{timeAgo(r.lastMessageAt)}</span>}
                  {r.unreadCount > 0 && <span className={styles.badge}>{r.unreadCount}</span>}
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
