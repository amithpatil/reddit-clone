import { api } from './apiClient';
import type { NotificationItem, RawNotification } from '../types/notification';

export async function fetchNotifications(): Promise<NotificationItem[]> {
  const raw = (await api.get('/api/notifications')) as RawNotification[];
  return raw.map((n) => ({ ...n, source: JSON.parse(n.source) }));
}

export function markNotificationRead(id: string): Promise<unknown> {
  return api.post(`/api/notifications/${id}/read`);
}

export function markAllNotificationsRead(): Promise<unknown> {
  return api.post('/api/notifications/read-all');
}
