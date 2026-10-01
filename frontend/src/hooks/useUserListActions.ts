import { useCallback, type Dispatch, type SetStateAction } from 'react';
import { followUser, unfollowUser, type PublicProfile } from '../lib/userApi';

interface UseUserListActionsResult {
  follow: (profile: PublicProfile) => void;
  unfollow: (profile: PublicProfile) => void;
}

// Shared by Search's People tab and UserConnections (followers/following lists) — both manage the same
// PublicProfile[] shape. Same optimistic-update-with-rollback shape useCommunityListActions established
// for a list of communities, just operating on isFollowing/followerCount instead of isMember/subscriberCount.
export function useUserListActions(setProfiles: Dispatch<SetStateAction<PublicProfile[]>>): UseUserListActionsResult {
  const follow = useCallback(
    (profile: PublicProfile) => {
      setProfiles((prev) =>
        prev.map((p) => (p.id === profile.id ? { ...p, isFollowing: true, followerCount: p.followerCount + 1 } : p)),
      );
      followUser(profile.username).catch(() => {
        setProfiles((prev) => prev.map((p) => (p.id === profile.id ? profile : p)));
      });
    },
    [setProfiles],
  );

  const unfollow = useCallback(
    (profile: PublicProfile) => {
      setProfiles((prev) =>
        prev.map((p) => (p.id === profile.id ? { ...p, isFollowing: false, followerCount: p.followerCount - 1 } : p)),
      );
      unfollowUser(profile.username).catch(() => {
        setProfiles((prev) => prev.map((p) => (p.id === profile.id ? profile : p)));
      });
    },
    [setProfiles],
  );

  return { follow, unfollow };
}
