import { useCallback, useEffect, useState } from 'react';
import { fetchModQueue, removeContent } from '../lib/moderationApi';
import type { ModQueueItem } from '../types/moderation';

interface UseModQueueResult {
  items: ModQueueItem[];
  loading: boolean;
  error: string | null;
  remove: (targetType: string, targetId: string, reason?: string) => Promise<void>;
  dropFromQueue: (targetType: string, targetId: string) => void;
}

// No pagination — LIST_PAGE_SIZE=100 on the backend already caps this to a size a single page comfortably
// covers for any one community's actual mod queue (unlike the home feed, this isn't a hot, ever-growing list).
export function useModQueue(communityName: string): UseModQueueResult {
  const [items, setItems] = useState<ModQueueItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setItems(await fetchModQueue(communityName));
    } catch {
      setError('Could not load the mod queue.');
    } finally {
      setLoading(false);
    }
  }, [communityName]);

  useEffect(() => {
    load();
  }, [load]);

  // Removing content also clears its mod_queue row server-side (ModerationService.removeContent) — drop it
  // from local state too instead of refetching the whole list.
  const remove = useCallback(
    async (targetType: string, targetId: string, reason?: string) => {
      await removeContent(communityName, targetType, targetId, reason);
      setItems((prev) => prev.filter((i) => !(i.targetType === targetType && i.targetId === targetId)));
    },
    [communityName],
  );

  // ModerationService.resolveReport/dismissReport each unconditionally delete the target's mod_queue row
  // server-side (not just when every report on it is handled) — call this after either succeeds so the row
  // doesn't linger in the UI until the next full reload, without making a redundant API call to do it.
  const dropFromQueue = useCallback((targetType: string, targetId: string) => {
    setItems((prev) => prev.filter((i) => !(i.targetType === targetType && i.targetId === targetId)));
  }, []);

  return { items, loading, error, remove, dropFromQueue };
}
