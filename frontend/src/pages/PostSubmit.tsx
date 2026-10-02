import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { FlairPicker } from '../components/FlairPicker';
import { useCommunitySearch } from '../hooks/useCommunitySearch';
import { useGalleryUpload } from '../hooks/useGalleryUpload';
import { useMediaUpload } from '../hooks/useMediaUpload';
import { ApiError } from '../lib/apiClient';
import { submitPost } from '../lib/postApi';
import type { PostKind } from '../types/post';
import styles from './PostSubmit.module.css';

type Tab = 'text' | 'media' | 'gallery' | 'link';

export function PostSubmit() {
  const { user } = useAuth();
  const { communityName } = useParams();
  const navigate = useNavigate();

  const [pickerQuery, setPickerQuery] = useState('');
  const [debouncedPickerQuery, setDebouncedPickerQuery] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedPickerQuery(pickerQuery), 300);
    return () => clearTimeout(timer);
  }, [pickerQuery]);
  const picker = useCommunitySearch(debouncedPickerQuery);

  const [tab, setTab] = useState<Tab>('text');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [url, setUrl] = useState('');
  const [flairId, setFlairId] = useState<string | null>(null);
  const [nsfw, setNsfw] = useState(false);
  const [spoiler, setSpoiler] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const idempotencyKeyRef = useRef(crypto.randomUUID());

  const media = useMediaUpload();
  const gallery = useGalleryUpload();

  if (!user) {
    return (
      <div className={styles.wrapper}>
        <div className={styles.card}>
          <p>
            <Link to="/login">Log in</Link> to create a post.
          </p>
        </div>
      </div>
    );
  }

  if (!communityName) {
    return (
      <div className={styles.wrapper}>
        <div className={styles.card}>
          <h1 className={styles.title}>Choose a community</h1>
          <input
            className={styles.searchInput}
            type="search"
            placeholder="Search communities"
            value={pickerQuery}
            onChange={(e) => setPickerQuery(e.target.value)}
            autoFocus
          />
          {picker.loading && <p>Loading…</p>}
          {!picker.loading &&
            debouncedPickerQuery.trim() &&
            picker.communities.map((c) => (
              <button key={c.id} type="button" className={styles.pickerResult} onClick={() => navigate(`/r/${c.name}/submit`)}>
                <span>r/{c.name}</span>
                <span>{c.subscriberCount} members</span>
              </button>
            ))}
        </div>
      </div>
    );
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!title.trim()) {
      setError('A title is required.');
      return;
    }
    let kind: PostKind;
    if (tab === 'text') {
      kind = 'text';
      if (!body.trim()) {
        setError('Text posts need a body.');
        return;
      }
    } else if (tab === 'link') {
      kind = 'link';
      if (!/^https?:\/\//.test(url.trim())) {
        setError('Enter a valid URL starting with http:// or https://.');
        return;
      }
    } else if (tab === 'gallery') {
      kind = 'gallery';
      if (gallery.items.length < 2) {
        setError('Add at least 2 images for a gallery.');
        return;
      }
      if (gallery.items.some((i) => i.uploading)) {
        setError('Please wait for the uploads to finish.');
        return;
      }
      if (gallery.items.some((i) => i.error || !i.mediaId)) {
        setError('Remove any images that failed to upload before posting.');
        return;
      }
    } else {
      if (!media.mediaId || !media.kind) {
        setError(media.uploading ? 'Please wait for the upload to finish.' : 'Choose an image or video to upload.');
        return;
      }
      kind = media.kind;
    }

    setSubmitting(true);
    try {
      const post = await submitPost(
        communityName,
        {
          kind,
          title: title.trim(),
          body: kind === 'text' ? body.trim() : undefined,
          url: kind === 'link' ? url.trim() : undefined,
          mediaId: kind === 'image' || kind === 'video' ? media.mediaId! : undefined,
          mediaIds: kind === 'gallery' ? gallery.items.map((i) => i.mediaId!) : undefined,
          flairId: flairId ?? undefined,
          nsfw,
          spoiler,
        },
        idempotencyKeyRef.current,
      );
      navigate(`/r/${communityName}/comments/${post.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create your post. Please try again.');
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.wrapper}>
      <form className={styles.card} onSubmit={handleSubmit}>
        <h1 className={styles.title}>Create a post</h1>
        <p className={styles.communityLabel}>
          Posting to <strong>r/{communityName}</strong>
        </p>

        <div className={styles.tabs}>
          <button type="button" className={`${styles.tab} ${tab === 'text' ? styles.tabActive : ''}`} onClick={() => setTab('text')}>
            Text
          </button>
          <button type="button" className={`${styles.tab} ${tab === 'media' ? styles.tabActive : ''}`} onClick={() => setTab('media')}>
            Images &amp; Video
          </button>
          <button type="button" className={`${styles.tab} ${tab === 'gallery' ? styles.tabActive : ''}`} onClick={() => setTab('gallery')}>
            Gallery
          </button>
          <button type="button" className={`${styles.tab} ${tab === 'link' ? styles.tabActive : ''}`} onClick={() => setTab('link')}>
            Link
          </button>
        </div>

        {error && <p className={styles.error}>{error}</p>}

        <div className={styles.field}>
          <div className={styles.labelRow}>
            <label className={styles.label} htmlFor="title">
              Title
            </label>
            <span className={styles.charCount}>{title.length}/300</span>
          </div>
          <input id="title" className={styles.input} value={title} onChange={(e) => setTitle(e.target.value.slice(0, 300))} required />
        </div>

        {tab === 'text' && (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="body">
              Text
            </label>
            <textarea id="body" className={styles.textarea} value={body} onChange={(e) => setBody(e.target.value.slice(0, 40000))} />
          </div>
        )}

        {tab === 'link' && (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="url">
              URL
            </label>
            <input
              id="url"
              className={styles.input}
              type="url"
              placeholder="https://example.com"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
          </div>
        )}

        {tab === 'media' && (
          <div className={styles.field}>
            <input
              className={styles.fileInput}
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif,video/mp4,video/quicktime,video/webm"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) media.selectFile(file);
              }}
            />
            {media.previewUrl &&
              (media.kind === 'video' ? (
                <video className={styles.preview} src={media.previewUrl} controls />
              ) : (
                <img className={styles.preview} src={media.previewUrl} alt="" />
              ))}
            {media.uploading && <p className={styles.uploadStatus}>Uploading…</p>}
            {media.error && <p className={styles.error}>{media.error}</p>}
          </div>
        )}

        {tab === 'gallery' && (
          <div className={styles.field}>
            <input
              className={styles.fileInput}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              onChange={(e) => {
                if (e.target.files && e.target.files.length > 0) gallery.addFiles(e.target.files);
                e.target.value = '';
              }}
            />
            {gallery.error && <p className={styles.error}>{gallery.error}</p>}
            {gallery.items.map((item, index) => (
              <div key={item.id} className={styles.galleryRow}>
                <img className={styles.galleryThumb} src={item.previewUrl} alt="" />
                <div className={styles.galleryRowInfo}>
                  <span>{index + 1}.</span>
                  {item.uploading && <span className={styles.uploadStatus}>Uploading…</span>}
                  {item.error && <span className={styles.error}>{item.error}</span>}
                </div>
                <div className={styles.galleryRowActions}>
                  <button
                    type="button"
                    className={styles.galleryMoveButton}
                    disabled={index === 0}
                    onClick={() => gallery.moveItem(item.id, 'up')}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    className={styles.galleryMoveButton}
                    disabled={index === gallery.items.length - 1}
                    onClick={() => gallery.moveItem(item.id, 'down')}
                  >
                    ↓
                  </button>
                  <button type="button" className={styles.galleryRemoveButton} onClick={() => gallery.removeItem(item.id)}>
                    Remove
                  </button>
                </div>
              </div>
            ))}
            {gallery.items.length > 0 && gallery.items.length < 2 && (
              <p className={styles.uploadStatus}>Add at least one more image.</p>
            )}
          </div>
        )}

        <FlairPicker communityName={communityName} value={flairId} onChange={setFlairId} />

        <label className={styles.checkboxRow}>
          <input type="checkbox" checked={nsfw} onChange={(e) => setNsfw(e.target.checked)} />
          NSFW
        </label>
        <label className={styles.checkboxRow}>
          <input type="checkbox" checked={spoiler} onChange={(e) => setSpoiler(e.target.checked)} />
          Spoiler
        </label>

        <button
          type="submit"
          className={styles.submit}
          disabled={submitting || media.uploading || gallery.items.some((i) => i.uploading)}
        >
          {submitting ? 'Posting…' : 'Post'}
        </button>
      </form>
    </div>
  );
}
