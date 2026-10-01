import { useCallback, type Dispatch, type SetStateAction } from 'react';
import { joinCommunity, leaveCommunity, requestToJoin } from '../lib/communityApi';
import type { Community } from '../types/community';

interface UseCommunityListActionsResult {
  join: (community: Community) => void;
  leave: (community: Community) => void;
  requestJoin: (community: Community) => void;
}

// Shared by useCommunityBrowse and useCommunitySearch — both manage the exact same Community[] shape, so
// unlike F4's pinned-vs-feed vote handlers (different state shapes, had to stay separate), sharing this
// one is natural rather than forced. Same optimistic-update-with-rollback shape useCommunity (F4)
// established for a single community, just operating on one entry of a list instead of one object.
export function useCommunityListActions(setCommunities: Dispatch<SetStateAction<Community[]>>): UseCommunityListActionsResult {
  const join = useCallback(
    (community: Community) => {
      setCommunities((prev) =>
        prev.map((c) => (c.id === community.id ? { ...c, isMember: true, subscriberCount: c.subscriberCount + 1 } : c)),
      );
      joinCommunity(community.name).catch(() => {
        setCommunities((prev) => prev.map((c) => (c.id === community.id ? community : c)));
      });
    },
    [setCommunities],
  );

  const leave = useCallback(
    (community: Community) => {
      setCommunities((prev) =>
        prev.map((c) => (c.id === community.id ? { ...c, isMember: false, subscriberCount: c.subscriberCount - 1 } : c)),
      );
      leaveCommunity(community.name).catch(() => {
        setCommunities((prev) => prev.map((c) => (c.id === community.id ? community : c)));
      });
    },
    [setCommunities],
  );

  const requestJoin = useCallback(
    (community: Community) => {
      setCommunities((prev) => prev.map((c) => (c.id === community.id ? { ...c, joinRequestStatus: 'pending' } : c)));
      requestToJoin(community.name).catch(() => {
        setCommunities((prev) => prev.map((c) => (c.id === community.id ? community : c)));
      });
    },
    [setCommunities],
  );

  return { join, leave, requestJoin };
}
