import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from '../lib/notificationApi';
import type { NotificationItem } from '../types/notification';

// No STOMP push exists for notifications today (chat's /queue infra is reused for chat_message delivery,
// but nothing calls convertAndSendToUser for notifications) — this polls instead, only while logged in.
const POLL_INTERVAL_MS = 30_000;

interface NotificationsContextValue {
  notifications: NotificationItem[];
  unreadCount: number;
  loading: boolean;
  refresh: () => Promise<void>;
  markRead: (id: string) => Promise<void>;
  markAllRead: () => Promise<void>;
}

const NotificationsContext = createContext<NotificationsContextValue | null>(null);

export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setNotifications(await fetchNotifications());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!user) {
      setNotifications([]);
      return;
    }
    refresh();
    const interval = setInterval(refresh, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [user, refresh]);

  // Optimistic local update, same shape as F8's useModQueue.dropFromQueue — the server call already
  // succeeded by the time this runs, so there's no need to refetch the whole list for one flipped flag.
  const markRead = useCallback(async (id: string) => {
    await markNotificationRead(id);
    setNotifications((prev) => prev.map((n) => (n.id === id ? { ...n, readAt: n.readAt ?? new Date().toISOString() } : n)));
  }, []);

  const markAllRead = useCallback(async () => {
    await markAllNotificationsRead();
    const now = new Date().toISOString();
    setNotifications((prev) => prev.map((n) => (n.readAt ? n : { ...n, readAt: now })));
  }, []);

  const unreadCount = notifications.filter((n) => !n.readAt).length;

  return (
    <NotificationsContext.Provider value={{ notifications, unreadCount, loading, refresh, markRead, markAllRead }}>
      {children}
    </NotificationsContext.Provider>
  );
}

export function useNotifications() {
  const ctx = useContext(NotificationsContext);
  if (!ctx) throw new Error('useNotifications must be used within NotificationsProvider');
  return ctx;
}
