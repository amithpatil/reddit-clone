import { useCallback, type Dispatch, type SetStateAction } from 'react';
import { castVote, removeVote } from '../lib/feedApi';
import type { Post } from '../types/post';

interface UsePostVoteActionResult {
  applyVote: (postId: string, dir: 1 | -1) => void;
}

// Shared by useFeed and usePostSearch — both manage the exact same Post[] shape, same precedent as
// useCommunityListActions sharing one optimistic-update implementation between useCommunityBrowse and
// useCommunitySearch. Optimistic: update score/myVote immediately, fire the request, and roll back on
// failure — matches the backend's own async vote model (POST /api/vote returns void; the outbox worker
// applies the real score change later), so the UI doesn't wait on it. Clicking the already-active
// direction un-votes; clicking the other direction swings the score by 2 (one vote removed, the opposite
// one added).
export function usePostVoteAction(setPosts: Dispatch<SetStateAction<Post[]>>): UsePostVoteActionResult {
  const applyVote = useCallback(
    (postId: string, dir: 1 | -1) => {
      let previous: Post | undefined;
      setPosts((prev) =>
        prev.map((p) => {
          if (p.id !== postId) return p;
          previous = p;
          const wasVote = p.myVote;
          const removing = wasVote === dir;
          const delta = removing ? -dir : wasVote ? dir * 2 : dir;
          return { ...p, score: p.score + delta, myVote: removing ? undefined : dir };
        }),
      );
      const wasVote = previous?.myVote;
      const action = wasVote === dir ? removeVote('post', postId) : castVote('post', postId, dir);
      action.catch(() => {
        if (previous) {
          const snapshot = previous;
          setPosts((prev) => prev.map((p) => (p.id === postId ? snapshot : p)));
        }
      });
    },
    [setPosts],
  );

  return { applyVote };
}
