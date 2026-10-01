import { useCallback, useEffect, useState } from 'react';
import { fetchBans, issueBan, liftBan } from '../lib/moderationApi';
import { fetchPublicProfile } from '../lib/userApi';
import { ApiError } from '../lib/apiClient';
import type { BanEntry } from '../types/moderation';

interface UseBansResult {
  bans: BanEntry[];
  loading: boolean;
  error: string | null;
  // Resolves the typed username to an id via the existing public profile lookup before issuing the ban —
  // POST /mod/ban needs a real userId, which a ban form only ever has a username for.
  banByUsername: (username: string, reason?: string, expiresAt?: string | null) => Promise<void>;
  unban: (userId: string) => Promise<void>;
}

export function useBans(communityName: string): UseBansResult {
  const [bans, setBans] = useState<BanEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setBans(await fetchBans(communityName));
    } catch {
      setError('Could not load the ban list.');
    } finally {
      setLoading(false);
    }
  }, [communityName]);

  useEffect(() => {
    load();
  }, [load]);

  const banByUsername = useCallback(
    async (username: string, reason?: string, expiresAt?: string | null) => {
      let profile;
      try {
        profile = await fetchPublicProfile(username);
      } catch (err) {
        throw new Error(err instanceof ApiError && err.status === 404 ? 'No such user.' : 'Could not look up that user.');
      }
      await issueBan(communityName, profile.id, reason, expiresAt);
      await load();
    },
    [communityName, load],
  );

  const unban = useCallback(
    async (userId: string) => {
      await liftBan(communityName, userId);
      setBans((prev) => prev.filter((b) => b.userId !== userId));
    },
    [communityName],
  );

  return { bans, loading, error, banByUsername, unban };
}
