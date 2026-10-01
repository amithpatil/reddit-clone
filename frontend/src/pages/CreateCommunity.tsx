import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../lib/apiClient';
import { createCommunity } from '../lib/communityApi';
import type { CommunityType } from '../types/community';
import styles from './CreateCommunity.module.css';

// Backend only enforces length (3-32, any character) — this stricter, Reddit-like charset is a frontend
// safety guard, not a fidelity choice: the name becomes a literal route segment (/r/{name}) everywhere in
// this app, so spaces/slashes/unicode would break routing and links even though the API would accept them.
const NAME_PATTERN = /^[A-Za-z0-9_]{3,21}$/;

const TYPE_OPTIONS: { value: CommunityType; title: string; description: string }[] = [
  { value: 'public', title: 'Public', description: 'Anyone can view, post, and comment.' },
  { value: 'restricted', title: 'Restricted', description: 'Anyone can view, but only approved users can post.' },
  { value: 'private', title: 'Private', description: 'Only approved users can view and post.' },
];

export function CreateCommunity() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState<CommunityType>('public');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!user) {
    return (
      <div className={styles.wrapper}>
        <div className={styles.card}>
          <p>
            <Link to="/login">Log in</Link> to create a community.
          </p>
        </div>
      </div>
    );
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!NAME_PATTERN.test(name)) {
      setError('Community names must be 3-21 characters: letters, numbers, and underscores only.');
      return;
    }
    setSubmitting(true);
    try {
      const community = await createCommunity(name, description.trim(), type);
      navigate(`/r/${community.name}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create this community. Please try again.');
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.wrapper}>
      <form className={styles.card} onSubmit={handleSubmit}>
        <h1 className={styles.title}>Create a community</h1>
        {error && <p className={styles.error}>{error}</p>}

        <div className={styles.field}>
          <label className={styles.label} htmlFor="name">
            Name
          </label>
          <div className={styles.prefixedInput}>
            <span className={styles.prefix}>r/</span>
            <input id="name" className={styles.input} value={name} onChange={(e) => setName(e.target.value)} required />
          </div>
          <span className={styles.hint}>3-21 characters, letters/numbers/underscores only.</span>
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="description">
            Description
          </label>
          <textarea id="description" className={styles.textarea} value={description} onChange={(e) => setDescription(e.target.value.slice(0, 2000))} />
        </div>

        <div className={styles.field}>
          <span className={styles.label}>Community type</span>
          {TYPE_OPTIONS.map((option) => (
            <label key={option.value} className={styles.typeOption}>
              <input type="radio" name="type" value={option.value} checked={type === option.value} onChange={() => setType(option.value)} />
              <span className={styles.typeOptionText}>
                <span className={styles.typeOptionTitle}>{option.title}</span>
                <span className={styles.typeOptionDescription}>{option.description}</span>
              </span>
            </label>
          ))}
        </div>

        <button type="submit" className={styles.submit} disabled={submitting}>
          {submitting ? 'Creating…' : 'Create Community'}
        </button>
      </form>
    </div>
  );
}
