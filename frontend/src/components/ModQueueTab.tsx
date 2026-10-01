import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useModQueue } from '../hooks/useModQueue';
import { decodeHtmlEntities } from '../lib/html';
import { dismissReport, fetchReportsForTarget, resolveReport } from '../lib/moderationApi';
import { timeAgo } from '../lib/time';
import { hasPermission, PERM_REMOVE_CONTENT } from '../types/moderation';
import type { ModQueueItem, ReportEntry } from '../types/moderation';
import styles from './ModQueueTab.module.css';

interface ModQueueTabProps {
  communityName: string;
  myPermissions: number | null | undefined;
}

export function ModQueueTab({ communityName, myPermissions }: ModQueueTabProps) {
  const { items, loading, error, remove, dropFromQueue } = useModQueue(communityName);
  const canAct = hasPermission(myPermissions, PERM_REMOVE_CONTENT);

  if (loading) return <div className={styles.state}>Loading…</div>;
  if (error) return <div className={styles.state}>{error}</div>;
  if (items.length === 0) return <div className={styles.state}>Nothing in the queue.</div>;

  return (
    <div>
      {items.map((item) => (
        <QueueRow
          key={`${item.targetType}-${item.targetId}`}
          item={item}
          communityName={communityName}
          canAct={canAct}
          onRemove={remove}
          onReportHandled={dropFromQueue}
        />
      ))}
    </div>
  );
}

interface QueueRowProps {
  item: ModQueueItem;
  communityName: string;
  canAct: boolean;
  onRemove: (targetType: string, targetId: string, reason?: string) => Promise<void>;
  onReportHandled: (targetType: string, targetId: string) => void;
}

function QueueRow({ item, communityName, canAct, onRemove, onReportHandled }: QueueRowProps) {
  const [expanded, setExpanded] = useState(false);
  const [reports, setReports] = useState<ReportEntry[] | null>(null);
  const [reportsError, setReportsError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const postHref = item.targetType === 'post'
    ? `/r/${communityName}/comments/${item.targetId}`
    : null; // a reported comment's own post id isn't carried on the queue entry — no link for those today

  const toggleExpanded = async () => {
    if (expanded) {
      setExpanded(false);
      return;
    }
    setExpanded(true);
    if (reports === null) {
      try {
        setReports(await fetchReportsForTarget(communityName, item.targetType, item.targetId));
      } catch {
        setReportsError('Could not load the individual reports.');
      }
    }
  };

  // ModerationService.resolveReport/dismissReport each unconditionally delete this target's mod_queue row
  // server-side, not just once every report on it is handled — onReportHandled mirrors that by dropping
  // the whole row from the parent list, not just this one report's status within it.
  const handleResolve = async (reportId: string) => {
    setBusy(true);
    try {
      await resolveReport(communityName, reportId);
      setReports((prev) => (prev ? prev.map((r) => (r.id === reportId ? { ...r, status: 'resolved' } : r)) : prev));
      onReportHandled(item.targetType, item.targetId);
    } finally {
      setBusy(false);
    }
  };

  const handleDismiss = async (reportId: string) => {
    setBusy(true);
    try {
      await dismissReport(communityName, reportId);
      setReports((prev) => (prev ? prev.map((r) => (r.id === reportId ? { ...r, status: 'dismissed' } : r)) : prev));
      onReportHandled(item.targetType, item.targetId);
    } finally {
      setBusy(false);
    }
  };

  const handleRemove = async () => {
    setBusy(true);
    try {
      await onRemove(item.targetType, item.targetId, 'removed from mod queue');
    } finally {
      setBusy(false);
    }
  };

  const preview = decodeHtmlEntities(item.preview) ?? '[content removed]';

  return (
    <article className={styles.row}>
      <div className={styles.rowHeader}>
        <span className={styles.badge}>{item.targetType}</span>
        <span className={styles.reportCount}>{item.reportCount} report{item.reportCount === 1 ? '' : 's'}</span>
        <span className={styles.time}>first reported {timeAgo(item.firstReportedAt)}</span>
      </div>
      <p className={styles.preview}>
        {postHref ? <Link to={postHref}>{preview}</Link> : preview}
      </p>
      <div className={styles.meta}>by u/{item.authorUsername ?? '[deleted]'}</div>
      <div className={styles.actions}>
        <button type="button" className={styles.linkButton} onClick={toggleExpanded}>
          {expanded ? 'Hide reports' : 'View reports'}
        </button>
        {canAct && (
          <button type="button" className={styles.removeButton} disabled={busy} onClick={handleRemove}>
            Remove
          </button>
        )}
      </div>
      {expanded && (
        <div className={styles.reportsList}>
          {reportsError && <div className={styles.state}>{reportsError}</div>}
          {reports === null && !reportsError && <div className={styles.state}>Loading…</div>}
          {reports?.map((r) => (
            <div key={r.id} className={styles.reportRow}>
              <div>
                <strong>u/{r.reporterUsername ?? '[deleted]'}</strong>: {r.reason}
                <span className={styles.reportStatus}> · {r.status}</span>
              </div>
              {canAct && r.status === 'open' && (
                <div className={styles.reportActions}>
                  <button type="button" disabled={busy} onClick={() => handleResolve(r.id)}>
                    Resolve
                  </button>
                  <button type="button" disabled={busy} onClick={() => handleDismiss(r.id)}>
                    Dismiss
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </article>
  );
}
