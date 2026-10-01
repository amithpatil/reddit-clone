import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { fetchMyCommentVotes, fetchPostWithComments, postComment } from '../lib/commentApi';
import { castVote, removeVote } from '../lib/feedApi';
import type { CommentNode, CommentSortType } from '../types/comment';
import type { Post } from '../types/post';

function flattenIds(nodes: CommentNode[]): string[] {
  return nodes.flatMap((n) => [n.id, ...flattenIds(n.replies)]);
}

function mergeVotes(nodes: CommentNode[], votes: Record<string, 1 | -1>): CommentNode[] {
  return nodes.map((n) => ({ ...n, myVote: votes[n.id], replies: mergeVotes(n.replies, votes) }));
}

function updateNode(nodes: CommentNode[], id: string, updater: (n: CommentNode) => CommentNode): CommentNode[] {
  return nodes.map((n) => {
    if (n.id === id) return updater(n);
    if (n.replies.length === 0) return n;
    return { ...n, replies: updateNode(n.replies, id, updater) };
  });
}

interface UsePostDetailResult {
  post: Post | null;
  comments: CommentNode[];
  loading: boolean;
  error: string | null;
  applyPostVote: (dir: 1 | -1) => void;
  applyCommentVote: (commentId: string, dir: 1 | -1) => void;
  submitComment: (parentId: string | null, body: string) => Promise<void>;
}

export function usePostDetail(communityName: string, postId: string, sort: CommentSortType): UsePostDetailResult {
  const { user } = useAuth();
  const [post, setPost] = useState<Post | null>(null);
  const [comments, setComments] = useState<CommentNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const data = await fetchPostWithComments(communityName, postId, sort);
      if (id !== requestId.current) return;
      let tree = data.comments;
      if (user) {
        try {
          const votes = await fetchMyCommentVotes(flattenIds(tree));
          if (id !== requestId.current) return;
          tree = mergeVotes(tree, votes);
        } catch {
          // Vote-state overlay is best-effort — the thread still renders without it.
        }
      }
      setPost(data.post);
      setComments(tree);
    } catch {
      if (id === requestId.current) setError('Could not load this post. It may have been removed.');
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, [communityName, postId, sort, user]);

  useEffect(() => {
    load();
  }, [load]);

  // Same optimistic toggle/swing logic as useFeed.applyVote, operating on this single post instead of a
  // list entry.
  const applyPostVote = useCallback(
    (dir: 1 | -1) => {
      let previous: Post | undefined;
      setPost((prev) => {
        if (!prev) return prev;
        previous = prev;
        const removing = prev.myVote === dir;
        const delta = removing ? -dir : prev.myVote ? dir * 2 : dir;
        return { ...prev, score: prev.score + delta, myVote: removing ? undefined : dir };
      });
      const action = previous?.myVote === dir ? removeVote('post', postId) : castVote('post', postId, dir);
      action.catch(() => {
        if (previous) {
          const snapshot = previous;
          setPost(snapshot);
        }
      });
    },
    [postId],
  );

  // Optimistic, same toggle/swing logic as useFeed.applyVote, just updating a node anywhere in the
  // nested tree instead of a flat list.
  const applyCommentVote = useCallback((commentId: string, dir: 1 | -1) => {
    let previous: CommentNode | undefined;
    setComments((prev) =>
      updateNode(prev, commentId, (n) => {
        previous = n;
        const removing = n.myVote === dir;
        const delta = removing ? -dir : n.myVote ? dir * 2 : dir;
        return { ...n, score: n.score + delta, myVote: removing ? undefined : dir };
      }),
    );
    const action = previous?.myVote === dir ? removeVote('comment', commentId) : castVote('comment', commentId, dir);
    action.catch(() => {
      if (previous) {
        const snapshot = previous;
        setComments((prev) => updateNode(prev, commentId, () => snapshot));
      }
    });
  }, []);

  // Simplest correct option: post, then refetch the whole tree — constructing a locally-shaped
  // CommentNode by hand (right author username, ranks, nesting position) isn't worth it for a feature
  // whose comment counts are small by design (see F3's plan).
  const submitComment = useCallback(
    async (parentId: string | null, body: string) => {
      await postComment(postId, parentId, body);
      await load();
    },
    [postId, load],
  );

  return { post, comments, loading, error, applyPostVote, applyCommentVote, submitComment };
}
