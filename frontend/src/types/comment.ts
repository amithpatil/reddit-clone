export interface CommentNode {
  id: string;
  postId: string;
  parentId: string | null;
  path: string;
  depth: number;
  authorId: string;
  authorUsername: string | null;
  body: string;
  score: number;
  ups: number;
  downs: number;
  bestRank: number;
  controversialRank: number;
  childCount: number;
  removed: boolean;
  createdAt: string;
  replies: CommentNode[];
  // Never sent by the backend — merged in client-side from GET /api/vote/mine?targetType=comment,
  // same reasoning as Post.myVote (see types/post.ts).
  myVote?: 1 | -1;
}

export type CommentSortType = 'best' | 'top' | 'new' | 'old' | 'controversial';
