import { api } from './apiClient';
import type { CommentNode, CommentSortType } from '../types/comment';
import type { Post } from '../types/post';

interface PostWithCommentsResponse {
  post: Post;
  comments: CommentNode[];
}

export function fetchPostWithComments(
  communityName: string,
  postId: string,
  sort: CommentSortType,
): Promise<PostWithCommentsResponse> {
  return api.get(`/r/${communityName}/comments/${postId}?sort=${sort}`) as Promise<PostWithCommentsResponse>;
}

export function postComment(postId: string, parentId: string | null, body: string): Promise<unknown> {
  return api.post('/api/comment', { postId, parentId, body });
}

// Returns a map of commentId -> direction for whichever of the given ids the current user has voted on;
// an id absent from the result means no vote. Only call this when logged in — the endpoint requires auth.
export async function fetchMyCommentVotes(commentIds: string[]): Promise<Record<string, 1 | -1>> {
  if (commentIds.length === 0) return {};
  const params = new URLSearchParams({ targetType: 'comment', targetIds: commentIds.join(',') });
  return (await api.get(`/api/vote/mine?${params.toString()}`)) as Record<string, 1 | -1>;
}
