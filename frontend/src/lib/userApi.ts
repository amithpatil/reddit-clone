import { api } from './apiClient';
import type { Listing } from '../types/listing';
import type { Post } from '../types/post';
import type { UserComment } from '../types/comment';

export interface PublicProfile {
  // F8: lets the moderation dashboard resolve a username typed into a ban form to the id POST /mod/ban
  // actually needs, via this same already-public endpoint.
  id: string;
  username: string;
  karmaPost: number;
  karmaComment: number;
  createdAt: string;
  status: 'active' | 'banned' | 'deleted';
}

export function fetchPublicProfile(username: string): Promise<PublicProfile> {
  return api.get(`/user/${username}/about`) as Promise<PublicProfile>;
}

// Not paginated — GET /user/search returns a single capped page server-side, same "relevance ranking
// isn't a stable keyset sort key" reasoning as searchCommunities/the backend's own search queries.
export function searchUsers(query: string): Promise<PublicProfile[]> {
  return api.get(`/user/search?q=${encodeURIComponent(query)}`) as Promise<PublicProfile[]>;
}

export function fetchUserPosts(username: string, after?: string | null): Promise<Listing<Post>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/user/${username}/submitted${query}`) as Promise<Listing<Post>>;
}

export function fetchUserComments(username: string, after?: string | null): Promise<Listing<UserComment>> {
  const query = after ? `?after=${encodeURIComponent(after)}` : '';
  return api.get(`/user/${username}/comments${query}`) as Promise<Listing<UserComment>>;
}
