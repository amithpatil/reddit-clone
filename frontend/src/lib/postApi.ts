import { api } from './apiClient';
import type { Post, PostKind } from '../types/post';

export interface CreatePostRequest {
  kind: PostKind;
  title: string;
  body?: string;
  url?: string;
  mediaId?: string;
  flairId?: string;
  nsfw?: boolean;
  spoiler?: boolean;
}

export function submitPost(communityName: string, request: CreatePostRequest, idempotencyKey: string): Promise<Post> {
  return api.post(`/r/${communityName}/submit`, request, { 'Idempotency-Key': idempotencyKey }) as Promise<Post>;
}
