import { api } from './apiClient';
import type { CommentNode, CommentSortType } from '../types/comment';
import type { Listing } from '../types/listing';
import type { Post } from '../types/post';

interface PostWithCommentsResponse {
  post: Post;
  comments: Listing<CommentNode>;
}

export function fetchPostWithComments(
  communityName: string,
  postId: string,
  sort: CommentSortType,
  after?: string | null,
): Promise<PostWithCommentsResponse> {
  const params = new URLSearchParams({ sort });
  if (after) params.set('after', after);
  return api.get(`/r/${communityName}/comments/${postId}?${params.toString()}`) as Promise<PostWithCommentsResponse>;
}

export function postComment(postId: string, parentId: string | null, body: string): Promise<unknown> {
  return api.post('/api/comment', { postId, parentId, body });
}

// GET /api/morechildren — the next page of one specific comment's direct children (not deeper), for the
// "N more replies" affordance CommentThread shows when childCount exceeds replies.length.
export function fetchMoreChildren(
  postId: string,
  parentId: string,
  sort: CommentSortType,
  after?: string | null,
): Promise<Listing<CommentNode>> {
  const params = new URLSearchParams({ postId, parentId, sort });
  if (after) params.set('after', after);
  return api.get(`/api/morechildren?${params.toString()}`) as Promise<Listing<CommentNode>>;
}

// Returns a map of commentId -> direction for whichever of the given ids the current user has voted on;
// an id absent from the result means no vote. Only call this when logged in — the endpoint requires auth.
export async function fetchMyCommentVotes(commentIds: string[]): Promise<Record<string, 1 | -1>> {
  if (commentIds.length === 0) return {};
  const params = new URLSearchParams({ targetType: 'comment', targetIds: commentIds.join(',') });
  return (await api.get(`/api/vote/mine?${params.toString()}`)) as Record<string, 1 | -1>;
}
