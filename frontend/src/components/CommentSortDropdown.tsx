import { useSearchParams } from 'react-router-dom';
import type { CommentSortType } from '../types/comment';
import styles from './CommentSortDropdown.module.css';

const SORTS: { value: CommentSortType; label: string }[] = [
  { value: 'best', label: 'Best' },
  { value: 'top', label: 'Top' },
  { value: 'new', label: 'New' },
  { value: 'old', label: 'Old' },
  { value: 'controversial', label: 'Controversial' },
];

export function CommentSortDropdown() {
  const [searchParams, setSearchParams] = useSearchParams();
  const sort = (searchParams.get('commentSort') as CommentSortType) || 'best';

  const setSort = (next: CommentSortType) => {
    const params = new URLSearchParams(searchParams);
    params.set('commentSort', next);
    setSearchParams(params);
  };

  return (
    <div className={styles.wrapper}>
      <span className={styles.label}>Sort by:</span>
      <select className={styles.select} value={sort} onChange={(e) => setSort(e.target.value as CommentSortType)}>
        {SORTS.map((s) => (
          <option key={s.value} value={s.value}>
            {s.label}
          </option>
        ))}
      </select>
    </div>
  );
}
