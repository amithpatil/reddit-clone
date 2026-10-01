import { api } from './apiClient';
import type { Community, CommunityRule } from '../types/community';
import type { Post } from '../types/post';

export function fetchCommunityAbout(name: string): Promise<Community> {
  return api.get(`/r/${name}/about`) as Promise<Community>;
}

export function fetchCommunityRules(name: string): Promise<CommunityRule[]> {
  return api.get(`/r/${name}/rules`) as Promise<CommunityRule[]>;
}

export function fetchPinnedPosts(name: string): Promise<Post[]> {
  return api.get(`/r/${name}/pinned`) as Promise<Post[]>;
}

export function joinCommunity(name: string): Promise<unknown> {
  return api.post(`/r/${name}/subscribe`);
}

export function leaveCommunity(name: string): Promise<unknown> {
  return api.del(`/r/${name}/subscribe`);
}

export function requestToJoin(name: string): Promise<unknown> {
  return api.post(`/r/${name}/join-requests`);
}
