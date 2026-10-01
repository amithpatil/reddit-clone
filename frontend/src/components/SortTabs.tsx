import { useSearchParams } from 'react-router-dom';
import type { SortType, TopPeriod } from '../types/post';
import styles from './SortTabs.module.css';

const SORTS: { value: SortType; label: string }[] = [
  { value: 'hot', label: 'Hot' },
  { value: 'new', label: 'New' },
  { value: 'top', label: 'Top' },
  { value: 'rising', label: 'Rising' },
  { value: 'controversial', label: 'Controversial' },
];

const PERIODS: { value: TopPeriod; label: string }[] = [
  { value: 'hour', label: 'Past Hour' },
  { value: 'day', label: 'Today' },
  { value: 'week', label: 'This Week' },
  { value: 'month', label: 'This Month' },
  { value: 'year', label: 'This Year' },
  { value: 'all', label: 'All Time' },
];

export function SortTabs() {
  const [searchParams, setSearchParams] = useSearchParams();
  const sort = (searchParams.get('sort') as SortType) || 'hot';
  const period = (searchParams.get('t') as TopPeriod) || 'all';

  const setSort = (next: SortType) => {
    const params = new URLSearchParams(searchParams);
    params.set('sort', next);
    if (next !== 'top') params.delete('t');
    setSearchParams(params);
  };

  const setPeriod = (next: TopPeriod) => {
    const params = new URLSearchParams(searchParams);
    params.set('t', next);
    setSearchParams(params);
  };

  return (
    <div className={styles.bar}>
      {SORTS.map((s) => (
        <button
          key={s.value}
          type="button"
          className={`${styles.tab} ${sort === s.value ? styles.tabActive : ''}`}
          onClick={() => setSort(s.value)}
        >
          {s.label}
        </button>
      ))}
      <div className={styles.spacer} />
      {sort === 'top' && (
        <select className={styles.periodSelect} value={period} onChange={(e) => setPeriod(e.target.value as TopPeriod)}>
          {PERIODS.map((p) => (
            <option key={p.value} value={p.value}>
              {p.label}
            </option>
          ))}
        </select>
      )}
    </div>
  );
}
