import { useState } from 'react';
import { timeAgo } from '../lib/time';
import type { CommentNode } from '../types/comment';
import { ReplyBox } from './ReplyBox';
import { VoteControl } from './VoteControl';
import styles from './CommentThread.module.css';

// Mirrors CommentService.MAX_DEPTH on the backend — replying past this would just 400.
const MAX_DEPTH = 10;

interface CommentThreadProps {
  comment: CommentNode;
  onVote: (commentId: string, dir: 1 | -1) => void;
  onReply: (parentId: string, body: string) => Promise<void>;
}

export function CommentThread({ comment, onVote, onReply }: CommentThreadProps) {
  const [replying, setReplying] = useState(false);

  const handleReply = async (body: string) => {
    await onReply(comment.id, body);
    setReplying(false);
  };

  return (
    <div>
      <div className={styles.comment}>
        <div className={styles.voteColumn}>
          <VoteControl score={comment.score} myVote={comment.myVote} onVote={(dir) => onVote(comment.id, dir)} />
        </div>
        <div className={styles.body}>
          <div className={styles.meta}>
            <span className={styles.author}>u/{comment.authorUsername ?? '[deleted]'}</span>{' '}
            <span className={styles.time}>· {timeAgo(comment.createdAt)}</span>
          </div>
          <p className={comment.removed ? `${styles.text} ${styles.textRemoved}` : styles.text}>{comment.body}</p>
          {comment.depth < MAX_DEPTH && (
            <button type="button" className={styles.replyToggle} onClick={() => setReplying((r) => !r)}>
              Reply
            </button>
          )}
          {replying && (
            <ReplyBox placeholder="What are your thoughts?" submitLabel="Reply" onCancel={() => setReplying(false)} onSubmit={handleReply} />
          )}
        </div>
      </div>
      {comment.replies.length > 0 && (
        <div className={styles.replies}>
          {comment.replies.map((reply) => (
            <CommentThread key={reply.id} comment={reply} onVote={onVote} onReply={onReply} />
          ))}
        </div>
      )}
    </div>
  );
}
