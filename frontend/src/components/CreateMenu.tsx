import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import styles from './CreateMenu.module.css';

export function CreateMenu() {
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [open]);

  return (
    <div className={styles.wrapper} ref={wrapperRef}>
      <button type="button" className={styles.button} onClick={() => setOpen((o) => !o)}>
        + Create
      </button>
      {open && (
        <div className={styles.menu}>
          <Link to="/submit" className={styles.menuItem} onClick={() => setOpen(false)}>
            Post
          </Link>
          <Link to="/communities/create" className={styles.menuItem} onClick={() => setOpen(false)}>
            Community
          </Link>
        </div>
      )}
    </div>
  );
}
