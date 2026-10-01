import { api } from './apiClient';
import type { Listing } from '../types/listing';
import type { ChatMessageItem, ChatRoomSummary } from '../types/chat';

export async function createRoom(usernames: string[]): Promise<string> {
  const room = (await api.post('/api/chat/rooms', { participantUsernames: usernames })) as { id: string };
  return room.id;
}

export function listRooms(): Promise<ChatRoomSummary[]> {
  return api.get('/api/chat/rooms') as Promise<ChatRoomSummary[]>;
}

export function fetchMessages(roomId: string, after?: string | null): Promise<Listing<ChatMessageItem>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/api/chat/rooms/${roomId}/messages${query}`) as Promise<Listing<ChatMessageItem>>;
}

export function markRoomRead(roomId: string): Promise<unknown> {
  return api.post(`/api/chat/rooms/${roomId}/read`);
}
