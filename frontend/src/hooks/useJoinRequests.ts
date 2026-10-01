import { useCallback, useEffect, useState } from 'react';
import { approveJoinRequest, denyJoinRequest, fetchJoinRequests } from '../lib/moderationApi';
import type { JoinRequestEntry } from '../types/moderation';

interface UseJoinRequestsResult {
  requests: JoinRequestEntry[];
  loading: boolean;
  error: string | null;
  approve: (userId: string) => Promise<void>;
  deny: (userId: string) => Promise<void>;
}

export function useJoinRequests(communityName: string): UseJoinRequestsResult {
  const [requests, setRequests] = useState<JoinRequestEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRequests(await fetchJoinRequests(communityName));
    } catch {
      setError('Could not load join requests.');
    } finally {
      setLoading(false);
    }
  }, [communityName]);

  useEffect(() => {
    load();
  }, [load]);

  // The backend only ever lists "pending" requests (ModerationService.listJoinRequests) — once approved or
  // denied, a request simply drops off this list, so both actions just remove it from local state.
  const approve = useCallback(
    async (userId: string) => {
      await approveJoinRequest(communityName, userId);
      setRequests((prev) => prev.filter((r) => r.userId !== userId));
    },
    [communityName],
  );

  const deny = useCallback(
    async (userId: string) => {
      await denyJoinRequest(communityName, userId);
      setRequests((prev) => prev.filter((r) => r.userId !== userId));
    },
    [communityName],
  );

  return { requests, loading, error, approve, deny };
}
