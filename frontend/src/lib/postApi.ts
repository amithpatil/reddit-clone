import { api } from './apiClient';
import type { Post, PostKind } from '../types/post';

export interface CreatePostRequest {
  kind: PostKind;
  title: string;
  body?: string;
  url?: string;
  mediaId?: string;
  mediaIds?: string[];
  flairId?: string;
  nsfw?: boolean;
  spoiler?: boolean;
}

export function submitPost(communityName: string, request: CreatePostRequest, idempotencyKey: string): Promise<Post> {
  return api.post(`/r/${communityName}/submit`, request, { 'Idempotency-Key': idempotencyKey }) as Promise<Post>;
}

export function editPost(communityName: string, postId: string, body: string): Promise<Post> {
  return api.patch(`/r/${communityName}/posts/${postId}`, { body }) as Promise<Post>;
}

export function deletePost(communityName: string, postId: string): Promise<unknown> {
  return api.del(`/r/${communityName}/posts/${postId}`);
}
