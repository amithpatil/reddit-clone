import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import {
  fetchFollowStatus,
  fetchPublicProfile,
  followUser,
  unfollowUser,
  type PublicProfile,
} from '../lib/userApi';

interface UseUserProfileResult {
  profile: PublicProfile | null;
  loading: boolean;
  error: string | null;
  actionError: string | null;
  follow: () => Promise<void>;
  unfollow: () => Promise<void>;
}

// Extracted from UserProfile.tsx's previous inline fetch (mirrors useCommunity.ts) so this page can also
// own follow/unfollow actions with the same snapshot -> optimistic-update -> rollback-on-failure shape
// useCommunity's join/leave/requestJoin already established.
export function useUserProfile(username: string): UseUserProfileResult {
  const { user: viewer } = useAuth();
  const [profile, setProfile] = useState<PublicProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const requestId = useRef(0);

  useEffect(() => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const p = await fetchPublicProfile(username);
        if (id !== requestId.current) return;
        // isFollowing is always null straight off GET /user/{username}/about (auth has no dependency on
        // follow) — resolved with a second, parallel call only when there's a logged-in viewer who isn't
        // looking at their own profile, since that's the only case where the value is ever shown.
        if (viewer && viewer.username !== username) {
          try {
            const status = await fetchFollowStatus(username);
            if (id !== requestId.current) return;
            setProfile({ ...p, isFollowing: status.isFollowing });
            return;
          } catch {
            // Best-effort — the profile still renders with isFollowing left null (Follow button defaults
            // to its "not following" state) rather than blocking the whole page on this one extra call.
          }
        }
        setProfile(p);
      } catch (err) {
        if (id !== requestId.current) return;
        setError(err instanceof ApiError && err.status === 404 ? 'No such user.' : 'Could not load this profile.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [username, viewer]);

  const follow = useCallback(async () => {
    if (!profile) return;
    setActionError(null);
    const snapshot = profile;
    setProfile({ ...profile, isFollowing: true, followerCount: profile.followerCount + 1 });
    try {
      await followUser(username);
    } catch (err) {
      setProfile(snapshot);
      setActionError(err instanceof ApiError ? err.message : 'Could not follow this user.');
    }
  }, [profile, username]);

  const unfollow = useCallback(async () => {
    if (!profile) return;
    setActionError(null);
    const snapshot = profile;
    setProfile({ ...profile, isFollowing: false, followerCount: profile.followerCount - 1 });
    try {
      await unfollowUser(username);
    } catch (err) {
      setProfile(snapshot);
      setActionError(err instanceof ApiError ? err.message : 'Could not unfollow this user.');
    }
  }, [profile, username]);

  return { profile, loading, error, actionError, follow, unfollow };
}
