import { useState } from 'react';
import { ApiError } from '../lib/apiClient';
import styles from './OwnContentActions.module.css';

interface OwnContentActionsProps {
  viewerId: string | undefined;
  authorId: string;
  removed: boolean;
  deleted: boolean;
  canEdit: boolean;
  onEdit: () => void;
  onDelete: () => Promise<void>;
}

// Only the author sees these, and only while the content is still theirs to change — a moderator-removed
// or already-deleted item renders nothing here (the server rejects those writes too).
export function OwnContentActions({ viewerId, authorId, removed, deleted, canEdit, onEdit, onDelete }: OwnContentActionsProps) {
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!viewerId || viewerId !== authorId || removed || deleted) {
    return null;
  }

  const handleConfirm = async () => {
    setDeleting(true);
    setError(null);
    try {
      await onDelete();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not delete. Please try again.');
      setConfirming(false);
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className={styles.wrapper}>
      {confirming ? (
        <div className={styles.confirmRow}>
          <span className={styles.confirmText}>Delete this? This can't be undone.</span>
          <button type="button" className={styles.dangerButton} disabled={deleting} onClick={handleConfirm}>
            {deleting ? 'Deleting…' : 'Confirm'}
          </button>
          <button type="button" className={styles.actionButton} disabled={deleting} onClick={() => setConfirming(false)}>
            Cancel
          </button>
        </div>
      ) : (
        <div className={styles.actions}>
          {canEdit && (
            <button type="button" className={styles.actionButton} onClick={onEdit}>
              Edit
            </button>
          )}
          <button type="button" className={styles.actionButton} onClick={() => setConfirming(true)}>
            Delete
          </button>
        </div>
      )}
      {error && <p className={styles.error}>{error}</p>}
    </div>
  );
}
