export type CommunityType = 'public' | 'restricted' | 'private';

export interface Community {
  id: string;
  name: string;
  type: CommunityType;
  description: string | null;
  creatorId: string;
  subscriberCount: number;
  createdAt: string;
  // Never sent to an anonymous viewer, and never attached at all outside GET /r/{name}/about (e.g. browse
  // or search) — see CommunityService.attachViewerContext.
  isMember?: boolean | null;
  isModerator?: boolean | null;
  joinRequestStatus?: 'pending' | 'approved' | 'denied' | null;
}

export interface CommunityRule {
  title: string;
  description: string;
}
