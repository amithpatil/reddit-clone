import { Client } from '@stomp/stompjs';
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import { API_BASE_URL, getCurrentAccessToken } from '../lib/apiClient';
import { createRoom, fetchMessages, listRooms, markRoomRead as apiMarkRoomRead } from '../lib/chatApi';
import type { ChatMessageItem, ChatRoomSummary } from '../types/chat';

interface ChatContextValue {
  rooms: ChatRoomSummary[];
  loadingRooms: boolean;
  totalUnread: number;
  messagesByRoom: Record<string, ChatMessageItem[]>;
  hasMoreByRoom: Record<string, boolean>;
  sendError: string | null;
  refreshRooms: () => Promise<void>;
  startRoom: (usernames: string[]) => Promise<string>;
  loadHistory: (roomId: string) => Promise<void>;
  loadOlder: (roomId: string) => Promise<void>;
  sendMessage: (roomId: string, body: string) => void;
  markRoomRead: (roomId: string) => Promise<void>;
  setActiveRoom: (roomId: string | null) => void;
}

const ChatContext = createContext<ChatContextValue | null>(null);

// Keeps a room's preview fields (and sort order) current without a full re-fetch, the same
// optimistic-local-update shape used throughout this codebase since F8.
function applyPreview(rooms: ChatRoomSummary[], msg: ChatMessageItem, incrementUnread: boolean): ChatRoomSummary[] {
  const next = rooms.map((r) =>
    r.roomId === msg.roomId
      ? {
          ...r,
          lastMessageBody: msg.body,
          lastMessageSenderId: msg.senderId,
          lastMessageAt: msg.createdAt,
          unreadCount: incrementUnread ? r.unreadCount + 1 : r.unreadCount,
        }
      : r,
  );
  return next.sort((a, b) => {
    if (!a.lastMessageAt) return 1;
    if (!b.lastMessageAt) return -1;
    return b.lastMessageAt.localeCompare(a.lastMessageAt);
  });
}

export function ChatProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [rooms, setRooms] = useState<ChatRoomSummary[]>([]);
  const [loadingRooms, setLoadingRooms] = useState(false);
  const [messagesByRoom, setMessagesByRoom] = useState<Record<string, ChatMessageItem[]>>({});
  const [hasMoreByRoom, setHasMoreByRoom] = useState<Record<string, boolean>>({});
  const [sendError, setSendError] = useState<string | null>(null);

  const clientRef = useRef<Client | null>(null);
  const cursorsRef = useRef<Record<string, string | null>>({});
  // A ref, not state: nothing renders off "which room is active" directly, it only steers how an
  // incoming live message is handled (append+auto-read vs. append+bump-unread) — not worth a re-render.
  const activeRoomIdRef = useRef<string | null>(null);

  const refreshRooms = useCallback(async () => {
    setLoadingRooms(true);
    try {
      setRooms(await listRooms());
    } finally {
      setLoadingRooms(false);
    }
  }, []);

  const markRoomRead = useCallback(async (roomId: string) => {
    await apiMarkRoomRead(roomId);
    setRooms((prev) => prev.map((r) => (r.roomId === roomId ? { ...r, unreadCount: 0 } : r)));
  }, []);

  // A message pushed over /queue/chat for a room the viewer doesn't have open yet: bump its unread count
  // and preview, falling back to a full refresh only when the room is genuinely new to this session (its
  // own summary — otherParticipants etc. — can't be synthesized client-side from a bare ChatMessage).
  const handleIncoming = useCallback(
    (msg: ChatMessageItem) => {
      const isActive = msg.roomId === activeRoomIdRef.current;
      setMessagesByRoom((prev) => {
        const existing = prev[msg.roomId];
        if (!existing) return prev;
        return { ...prev, [msg.roomId]: [...existing, msg] };
      });
      setRooms((prev) => {
        if (!prev.some((r) => r.roomId === msg.roomId)) {
          refreshRooms();
          return prev;
        }
        return applyPreview(prev, msg, !isActive);
      });
      if (isActive) {
        markRoomRead(msg.roomId);
      }
    },
    [refreshRooms, markRoomRead],
  );

  useEffect(() => {
    if (!user) {
      clientRef.current?.deactivate();
      clientRef.current = null;
      setRooms([]);
      setMessagesByRoom({});
      setHasMoreByRoom({});
      return;
    }

    const client = new Client({
      brokerURL: API_BASE_URL.replace(/^http/, 'ws') + '/ws',
      reconnectDelay: 5000,
      // Re-read the current token on every connect AND every reconnect, so a refreshed token is always
      // picked up without extra plumbing — no proactive refresh-before-connect beyond that (see plan).
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${getCurrentAccessToken() ?? ''}` };
      },
      onConnect: () => {
        client.subscribe('/user/queue/chat', (message) => {
          handleIncoming(JSON.parse(message.body) as ChatMessageItem);
        });
        client.subscribe('/user/queue/errors', (message) => {
          setSendError(message.body);
        });
      },
    });
    clientRef.current = client;
    client.activate();
    refreshRooms();

    return () => {
      client.deactivate();
      if (clientRef.current === client) clientRef.current = null;
    };
  }, [user, refreshRooms, handleIncoming]);

  const startRoom = useCallback(
    async (usernames: string[]) => {
      const roomId = await createRoom(usernames);
      await refreshRooms();
      return roomId;
    },
    [refreshRooms],
  );

  const loadHistory = useCallback(async (roomId: string) => {
    const listing = await fetchMessages(roomId);
    const page = listing.data.children.map((c) => c.data).reverse(); // newest-first -> chronological
    cursorsRef.current[roomId] = listing.data.after;
    setMessagesByRoom((prev) => ({ ...prev, [roomId]: page }));
    setHasMoreByRoom((prev) => ({ ...prev, [roomId]: listing.data.after !== null }));
  }, []);

  const loadOlder = useCallback(async (roomId: string) => {
    const cursor = cursorsRef.current[roomId];
    if (!cursor) return;
    const listing = await fetchMessages(roomId, cursor);
    const older = listing.data.children.map((c) => c.data).reverse();
    cursorsRef.current[roomId] = listing.data.after;
    setMessagesByRoom((prev) => ({ ...prev, [roomId]: [...older, ...(prev[roomId] ?? [])] }));
    setHasMoreByRoom((prev) => ({ ...prev, [roomId]: listing.data.after !== null }));
  }, []);

  // Optimistic: the sender is never echoed their own message back over /queue/chat by design (only the
  // other participants get the push), so this local append is the only representation of a just-sent
  // message for the rest of the session — same tradeoff F8/F9's optimistic updates already accept.
  const sendMessage = useCallback(
    (roomId: string, body: string) => {
      const trimmed = body.trim();
      if (!trimmed || !user) return;
      const optimistic: ChatMessageItem = {
        id: `temp-${Date.now()}`,
        roomId,
        senderId: user.id,
        senderUsername: user.username,
        body: trimmed,
        createdAt: new Date().toISOString(),
      };
      setMessagesByRoom((prev) => ({ ...prev, [roomId]: [...(prev[roomId] ?? []), optimistic] }));
      setRooms((prev) => applyPreview(prev, optimistic, false));
      setSendError(null);
      try {
        clientRef.current?.publish({ destination: '/app/chat.send', body: JSON.stringify({ roomId, body: trimmed }) });
      } catch {
        setSendError('Could not send — not connected.');
      }
    },
    [user],
  );

  const setActiveRoom = useCallback((roomId: string | null) => {
    activeRoomIdRef.current = roomId;
  }, []);

  const totalUnread = rooms.reduce((sum, r) => sum + r.unreadCount, 0);

  return (
    <ChatContext.Provider
      value={{
        rooms,
        loadingRooms,
        totalUnread,
        messagesByRoom,
        hasMoreByRoom,
        sendError,
        refreshRooms,
        startRoom,
        loadHistory,
        loadOlder,
        sendMessage,
        markRoomRead,
        setActiveRoom,
      }}
    >
      {children}
    </ChatContext.Provider>
  );
}

export function useChat() {
  const ctx = useContext(ChatContext);
  if (!ctx) throw new Error('useChat must be used within ChatProvider');
  return ctx;
}
