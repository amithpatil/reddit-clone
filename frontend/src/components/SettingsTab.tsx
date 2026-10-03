import { useState } from 'react';
import { ApiError } from '../lib/apiClient';
import { updateCommunityDescription } from '../lib/moderationApi';
import styles from './SettingsTab.module.css';

// Mirrors the backend's @Size(max = 2000) on UpdateDescriptionRequest.
const MAX_DESCRIPTION_LENGTH = 2000;

interface SettingsTabProps {
  communityName: string;
  initialDescription: string | null;
  onSaved: (description: string) => void;
}

export function SettingsTab({ communityName, initialDescription, onSaved }: SettingsTabProps) {
  const [description, setDescription] = useState(initialDescription ?? '');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const unchanged = description === (initialDescription ?? '');

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const result = await updateCommunityDescription(communityName, description);
      const next = result.description ?? '';
      setDescription(next);
      onSaved(next);
      setSaved(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save the description. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className={styles.form}>
      <label className={styles.label} htmlFor="community-description">
        Community description
      </label>
      <textarea
        id="community-description"
        className={styles.textarea}
        value={description}
        maxLength={MAX_DESCRIPTION_LENGTH}
        onChange={(e) => {
          setDescription(e.target.value);
          setSaved(false);
        }}
      />
      <div className={styles.footer}>
        <span className={styles.count}>
          {description.length}/{MAX_DESCRIPTION_LENGTH}
        </span>
        <button type="button" className={styles.save} disabled={saving || unchanged} onClick={handleSave}>
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
      {saved && <p className={styles.saved}>Description saved.</p>}
      {error && <p className={styles.error}>{error}</p>}
    </div>
  );
}
