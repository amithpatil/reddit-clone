import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useChat } from '../chat/ChatContext';
import { decodeHtmlEntities } from '../lib/html';
import { timeAgo } from '../lib/time';
import styles from './ChatRoom.module.css';

export function ChatRoom() {
  const { roomId = '' } = useParams();
  const { user } = useAuth();
  const { rooms, messagesByRoom, hasMoreByRoom, sendError, loadHistory, loadOlder, sendMessage, markRoomRead, setActiveRoom } = useChat();
  const [loading, setLoading] = useState(true);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [draft, setDraft] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);
  const hasScrolledInitially = useRef(false);

  const room = rooms.find((r) => r.roomId === roomId);
  const messages = messagesByRoom[roomId] ?? [];
  const hasMore = hasMoreByRoom[roomId] ?? false;

  useEffect(() => {
    setActiveRoom(roomId);
    hasScrolledInitially.current = false;
    setLoading(true);
    (async () => {
      try {
        await loadHistory(roomId);
        await markRoomRead(roomId);
      } finally {
        setLoading(false);
      }
    })();
    return () => setActiveRoom(null);
  }, [roomId, loadHistory, markRoomRead, setActiveRoom]);

  useEffect(() => {
    if (!hasScrolledInitially.current && !loading && messages.length > 0) {
      bottomRef.current?.scrollIntoView();
      hasScrolledInitially.current = true;
    }
  }, [loading, messages.length]);

  const handleLoadOlder = async () => {
    setLoadingOlder(true);
    try {
      await loadOlder(roomId);
    } finally {
      setLoadingOlder(false);
    }
  };

  const handleSend = () => {
    if (!draft.trim()) return;
    sendMessage(roomId, draft);
    setDraft('');
    requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }));
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <Link to="/chat" className={styles.back}>
          ← Chat
        </Link>
        <h1 className={styles.title}>{room ? room.otherParticipants.join(', ') : '…'}</h1>
      </div>

      <div className={styles.messages}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : (
          <>
            {hasMore && (
              <button type="button" className={styles.loadOlder} disabled={loadingOlder} onClick={handleLoadOlder}>
                {loadingOlder ? 'Loading…' : 'Load earlier messages'}
              </button>
            )}
            {messages.map((m) => {
              const own = m.senderId === user?.id;
              return (
                <div key={m.id} className={`${styles.messageRow} ${own ? styles.own : ''}`}>
                  <div className={styles.bubble}>
                    {!own && <div className={styles.sender}>{m.senderUsername ?? '[deleted]'}</div>}
                    <div className={styles.body}>{decodeHtmlEntities(m.body)}</div>
                    <div className={styles.time}>{timeAgo(m.createdAt)}</div>
                  </div>
                </div>
              );
            })}
            <div ref={bottomRef} />
          </>
        )}
      </div>

      {sendError && <div className={styles.sendError}>{sendError}</div>}
      <div className={styles.compose}>
        <textarea
          className={styles.composeInput}
          placeholder="Message…"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={handleKeyDown}
          rows={1}
        />
        <button type="button" className={styles.sendButton} disabled={!draft.trim()} onClick={handleSend}>
          Send
        </button>
      </div>
    </div>
  );
}
