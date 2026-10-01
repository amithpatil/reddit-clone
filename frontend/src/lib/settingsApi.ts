import { api } from './apiClient';
import type { NotificationType } from '../types/notification';

// Named for the actual REST resource (UserSettings, GET/PATCH /api/v1/me/prefs) rather than folded into
// notificationApi.ts — F11 (Settings) will also need this same endpoint for nsfwBlur/privacyPrefs.
export interface UserSettings {
  nsfwBlur: boolean;
  privacyPrefs: Record<string, unknown>;
  notificationPrefs: Partial<Record<NotificationType, boolean>>;
}

export function fetchSettings(): Promise<UserSettings> {
  return api.get('/api/v1/me/prefs') as Promise<UserSettings>;
}

// AuthService.updateSettings merges notificationPrefs rather than replacing it, so sending just the
// changed type is safe and never clobbers other prefs.
export function updateNotificationPrefs(prefs: Partial<Record<NotificationType, boolean>>): Promise<UserSettings> {
  return api.patch('/api/v1/me/prefs', { notificationPrefs: prefs }) as Promise<UserSettings>;
}

export function updateNsfwBlur(value: boolean): Promise<UserSettings> {
  return api.patch('/api/v1/me/prefs', { nsfwBlur: value }) as Promise<UserSettings>;
}

// Same merge-not-replace semantics as notificationPrefs — sending just the changed key never clobbers
// other privacyPrefs keys (e.g. toggling theme doesn't wipe restrictChatToKnown, and vice versa).
export function updatePrivacyPrefs(prefs: Record<string, unknown>): Promise<UserSettings> {
  return api.patch('/api/v1/me/prefs', { privacyPrefs: prefs }) as Promise<UserSettings>;
}

export function deleteAccount(password: string): Promise<unknown> {
  return api.del('/api/v1/me', { password });
}
