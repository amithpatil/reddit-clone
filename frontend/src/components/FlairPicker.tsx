import { useEffect, useState } from 'react';
import { api } from '../lib/apiClient';
import type { Flair } from '../types/post';
import styles from './FlairPicker.module.css';

interface FlairPickerProps {
  communityName: string;
  value: string | null;
  onChange: (flairId: string | null) => void;
}

// Renders nothing at all when the community has no post flairs — matches Reddit's own behavior of not
// showing an empty picker, rather than an always-present-but-useless dropdown.
export function FlairPicker({ communityName, value, onChange }: FlairPickerProps) {
  const [flairs, setFlairs] = useState<Flair[]>([]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const results = (await api.get(`/r/${communityName}/flairs?type=post`)) as Flair[];
        if (!cancelled) setFlairs(results);
      } catch {
        if (!cancelled) setFlairs([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [communityName]);

  if (flairs.length === 0) return null;

  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor="flair">
        Flair (optional)
      </label>
      <select id="flair" className={styles.select} value={value ?? ''} onChange={(e) => onChange(e.target.value || null)}>
        <option value="">No flair</option>
        {flairs.map((flair) => (
          <option key={flair.id} value={flair.id}>
            {flair.text}
          </option>
        ))}
      </select>
    </div>
  );
}
