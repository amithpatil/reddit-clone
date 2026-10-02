import { useCallback, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { ApiError } from '../lib/apiClient';
import { followUser, unfollowUser, type PublicProfile } from '../lib/userApi';

interface UseUserListActionsResult {
  follow: (profile: PublicProfile) => void;
  unfollow: (profile: PublicProfile) => void;
  error: string | null;
}

// Shared by Search's People tab and UserConnections (followers/following lists) — both manage the same
// PublicProfile[] shape. Same optimistic-update-with-rollback shape useCommunityListActions established
// for a list of communities, just operating on isFollowing/followerCount instead of isMember/subscriberCount.
export function useUserListActions(setProfiles: Dispatch<SetStateAction<PublicProfile[]>>): UseUserListActionsResult {
  const [error, setError] = useState<string | null>(null);
  // Blocks a new follow()/unfollow() call for a profile while one is still in flight for it — prevents a
  // quick double-click from sending two requests the server could process out of order, same reasoning as
  // useUserProfile.ts's actionInFlight guard.
  const inFlight = useRef<Set<string>>(new Set());

  const follow = useCallback(
    (profile: PublicProfile) => {
      if (inFlight.current.has(profile.id)) return;
      inFlight.current.add(profile.id);
      setError(null);
      // Only the button state flips optimistically — the count is adjusted once the response says a real
      // change happened, since our own isFollowing guess can be wrong and a redundant follow is a no-op,
      // not an error, on the backend.
      setProfiles((prev) => prev.map((p) => (p.id === profile.id ? { ...p, isFollowing: true } : p)));
      followUser(profile.username)
        .then(({ changed }) => {
          if (changed) {
            setProfiles((prev) => prev.map((p) => (p.id === profile.id ? { ...p, followerCount: p.followerCount + 1 } : p)));
          }
        })
        .catch((err) => {
          setProfiles((prev) => prev.map((p) => (p.id === profile.id ? profile : p)));
          setError(err instanceof ApiError ? err.message : 'Could not follow this user.');
        })
        .finally(() => {
          inFlight.current.delete(profile.id);
        });
    },
    [setProfiles],
  );

  const unfollow = useCallback(
    (profile: PublicProfile) => {
      if (inFlight.current.has(profile.id)) return;
      inFlight.current.add(profile.id);
      setError(null);
      setProfiles((prev) => prev.map((p) => (p.id === profile.id ? { ...p, isFollowing: false } : p)));
      unfollowUser(profile.username)
        .then(({ changed }) => {
          if (changed) {
            setProfiles((prev) => prev.map((p) => (p.id === profile.id ? { ...p, followerCount: p.followerCount - 1 } : p)));
          }
        })
        .catch((err) => {
          setProfiles((prev) => prev.map((p) => (p.id === profile.id ? profile : p)));
          setError(err instanceof ApiError ? err.message : 'Could not unfollow this user.');
        })
        .finally(() => {
          inFlight.current.delete(profile.id);
        });
    },
    [setProfiles],
  );

  return { follow, unfollow, error };
}
