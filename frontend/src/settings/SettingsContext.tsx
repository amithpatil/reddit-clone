import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthContext';
import {
  fetchSettings,
  updateNotificationPrefs as apiUpdateNotificationPrefs,
  updateNsfwBlur as apiUpdateNsfwBlur,
  updatePrivacyPrefs as apiUpdatePrivacyPrefs,
  type UserSettings,
} from '../lib/settingsApi';
import type { NotificationType } from '../types/notification';

export type Theme = 'light' | 'dark';

interface SettingsContextValue {
  settings: UserSettings | null;
  loading: boolean;
  // Blurred by default even while logged out or settings haven't loaded yet — a fail-safe default, not
  // "unblurred until proven otherwise" — matching the backend column's own true default.
  nsfwBlurEffective: boolean;
  // undefined = no explicit choice made yet, follow the OS's prefers-color-scheme.
  theme: Theme | undefined;
  refresh: () => Promise<void>;
  updateNsfwBlur: (value: boolean) => Promise<void>;
  updatePrivacyPrefs: (prefs: Record<string, unknown>) => Promise<void>;
  updateNotificationPrefs: (prefs: Partial<Record<NotificationType, boolean>>) => Promise<void>;
  setTheme: (theme: Theme | undefined) => Promise<void>;
}

const SettingsContext = createContext<SettingsContextValue | null>(null);

export function SettingsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [settings, setSettings] = useState<UserSettings | null>(null);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setSettings(await fetchSettings());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!user) {
      setSettings(null);
      return;
    }
    refresh();
  }, [user, refresh]);

  const theme = (settings?.privacyPrefs.theme as Theme | undefined) ?? undefined;

  // Applied as early as every settings change, not just on first load, so toggling is instant without
  // waiting on the PATCH below to resolve.
  useEffect(() => {
    if (theme) {
      document.documentElement.dataset.theme = theme;
    } else {
      delete document.documentElement.dataset.theme;
    }
  }, [theme]);

  // Optimistic local merge before the API call resolves, no rollback on failure (just let the next
  // `refresh` or page reload reconcile) — the same shape F9's NotificationsInbox already established.
  const updateNsfwBlur = useCallback(async (value: boolean) => {
    setSettings((prev) => (prev ? { ...prev, nsfwBlur: value } : prev));
    await apiUpdateNsfwBlur(value);
  }, []);

  const updatePrivacyPrefs = useCallback(async (prefs: Record<string, unknown>) => {
    setSettings((prev) => (prev ? { ...prev, privacyPrefs: { ...prev.privacyPrefs, ...prefs } } : prev));
    await apiUpdatePrivacyPrefs(prefs);
  }, []);

  const updateNotificationPrefs = useCallback(async (prefs: Partial<Record<NotificationType, boolean>>) => {
    setSettings((prev) => (prev ? { ...prev, notificationPrefs: { ...prev.notificationPrefs, ...prefs } } : prev));
    await apiUpdateNotificationPrefs(prefs);
  }, []);

  const setTheme = useCallback(
    async (next: Theme | undefined) => {
      await updatePrivacyPrefs({ theme: next ?? null });
    },
    [updatePrivacyPrefs],
  );

  const nsfwBlurEffective = settings?.nsfwBlur ?? true;

  return (
    <SettingsContext.Provider
      value={{ settings, loading, nsfwBlurEffective, theme, refresh, updateNsfwBlur, updatePrivacyPrefs, updateNotificationPrefs, setTheme }}
    >
      {children}
    </SettingsContext.Provider>
  );
}

export function useSettings() {
  const ctx = useContext(SettingsContext);
  if (!ctx) throw new Error('useSettings must be used within SettingsProvider');
  return ctx;
}
