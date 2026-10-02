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
  // Blocks a new follow()/unfollow() call while one is still in flight for this profile — without this, a
  // quick follow-then-unfollow double click can send both requests concurrently, let the server process
  // them out of order (e.g. the fast unfollow arrives and no-ops before the slow follow has even landed,
  // which then recreates the row afterward), and leave the UI's final state out of sync with the server's.
  const actionInFlight = useRef(false);

  useEffect(() => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        // isFollowing is always null straight off GET /user/{username}/about (auth has no dependency on
        // follow) — resolved via a second call run in parallel with the profile fetch (not after it: the
        // status call only needs `username`, not the profile response), applied only when the viewer turns
        // out not to be looking at their own profile — using the canonical username the profile fetch
        // returns, not the raw route param, since usernames are case-insensitive (citext) and the two can
        // differ only in case for the same account.
        const shouldCheckStatus = viewer != null && viewer.username !== username;
        const [p, status] = await Promise.all([
          fetchPublicProfile(username),
          shouldCheckStatus ? fetchFollowStatus(username).catch(() => null) : Promise.resolve(null),
        ]);
        if (id !== requestId.current) return;
        const isFollowing = viewer && viewer.username !== p.username && status ? status.isFollowing : null;
        setProfile({ ...p, isFollowing });
      } catch (err) {
        if (id !== requestId.current) return;
        setError(err instanceof ApiError && err.status === 404 ? 'No such user.' : 'Could not load this profile.');
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    })();
  }, [username, viewer]);

  const follow = useCallback(async () => {
    if (!profile || actionInFlight.current) return;
    actionInFlight.current = true;
    setActionError(null);
    const snapshot = profile;
    // Only the button state flips optimistically — the count is adjusted once the response says a real
    // change happened, not eagerly, since our own isFollowing guess (from a best-effort status fetch) can
    // be wrong and the backend treats a redundant follow as a no-op rather than an error.
    setProfile({ ...profile, isFollowing: true });
    try {
      const { changed } = await followUser(username);
      if (changed) {
        setProfile((prev) => (prev ? { ...prev, followerCount: prev.followerCount + 1 } : prev));
      }
    } catch (err) {
      setProfile(snapshot);
      setActionError(err instanceof ApiError ? err.message : 'Could not follow this user.');
    } finally {
      actionInFlight.current = false;
    }
  }, [profile, username]);

  const unfollow = useCallback(async () => {
    if (!profile || actionInFlight.current) return;
    actionInFlight.current = true;
    setActionError(null);
    const snapshot = profile;
    setProfile({ ...profile, isFollowing: false });
    try {
      const { changed } = await unfollowUser(username);
      if (changed) {
        setProfile((prev) => (prev ? { ...prev, followerCount: prev.followerCount - 1 } : prev));
      }
    } catch (err) {
      setProfile(snapshot);
      setActionError(err instanceof ApiError ? err.message : 'Could not unfollow this user.');
    } finally {
      actionInFlight.current = false;
    }
  }, [profile, username]);

  return { profile, loading, error, actionError, follow, unfollow };
}
