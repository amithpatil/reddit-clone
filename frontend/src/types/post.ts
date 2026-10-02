export interface MediaView {
  thumbnailUrl: string | null;
  displayUrl: string | null;
  width: number | null;
  height: number | null;
  durationSeconds: number | null;
  processingStatus: 'pending' | 'uploaded' | 'processing' | 'ready' | 'failed';
}

export interface Flair {
  id: string;
  communityId: string;
  text: string;
  color: string;
  type: 'user' | 'post';
  createdAt: string;
}

export type PostKind = 'text' | 'link' | 'image' | 'video' | 'gallery';

export interface Post {
  id: string;
  communityId: string;
  communityName: string | null;
  authorId: string;
  authorUsername: string | null;
  kind: PostKind;
  title: string;
  body: string | null;
  url: string | null;
  mediaId: string | null;
  media: MediaView | null;
  // Only populated for kind="gallery" — null for every other kind, same as mediaId/media staying null for
  // a gallery post. Ordered: index 0 is the first image in the gallery.
  mediaItems: MediaView[] | null;
  flairId: string | null;
  flair: Flair | null;
  nsfw: boolean;
  spoiler: boolean;
  score: number;
  commentCount: number;
  ups: number;
  downs: number;
  hotRank: number;
  risingRank: number;
  controversialRank: number;
  pinned: boolean;
  locked: boolean;
  createdAt: string;
  // Never sent by the backend on the feed response itself — merged in client-side from a separate
  // GET /api/vote/mine call (see useFeed), because the vote module can't attach it to Post without
  // creating a module-boundary cycle (vote already depends on post).
  myVote?: 1 | -1;
}

export type SortType = 'hot' | 'new' | 'top' | 'rising' | 'controversial';

export type TopPeriod = 'hour' | 'day' | 'week' | 'month' | 'year' | 'all';
