import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import styles from './ReplyBox.module.css';

interface ReplyBoxProps {
  placeholder?: string;
  submitLabel?: string;
  onCancel?: () => void;
  onSubmit: (body: string) => Promise<void>;
}

export function ReplyBox({ placeholder = 'What are your thoughts?', submitLabel = 'Comment', onCancel, onSubmit }: ReplyBoxProps) {
  const { user } = useAuth();
  const [body, setBody] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!user) {
    return (
      <div className={styles.loginPrompt}>
        <Link to="/login">Log in</Link> to leave a comment.
      </div>
    );
  }

  const handleSubmit = async () => {
    if (!body.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit(body.trim());
      setBody('');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not post your comment. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.box}>
      {error && <p className={styles.error}>{error}</p>}
      <textarea
        className={styles.textarea}
        placeholder={placeholder}
        value={body}
        onChange={(e) => setBody(e.target.value)}
      />
      <div className={styles.actions}>
        {onCancel && (
          <button type="button" className={styles.cancel} onClick={onCancel}>
            Cancel
          </button>
        )}
        <button type="button" className={styles.submit} disabled={submitting || !body.trim()} onClick={handleSubmit}>
          {submitting ? 'Posting…' : submitLabel}
        </button>
      </div>
    </div>
  );
}
