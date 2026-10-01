import { api } from './apiClient';
import type { Listing } from '../types/listing';
import type { Post } from '../types/post';
import type { UserComment } from '../types/comment';

export interface PublicProfile {
  username: string;
  karmaPost: number;
  karmaComment: number;
  createdAt: string;
  status: 'active' | 'banned' | 'deleted';
}

export function fetchPublicProfile(username: string): Promise<PublicProfile> {
  return api.get(`/user/${username}/about`) as Promise<PublicProfile>;
}

export function fetchUserPosts(username: string, after?: string | null): Promise<Listing<Post>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/user/${username}/submitted${query}`) as Promise<Listing<Post>>;
}

export function fetchUserComments(username: string, after?: string | null): Promise<Listing<UserComment>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/user/${username}/comments${query}`) as Promise<Listing<UserComment>>;
}
