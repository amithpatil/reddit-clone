import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { CreateMenu } from './CreateMenu';
import { Logo } from './Logo';
import { NotificationBell } from './NotificationBell';
import styles from './NavBar.module.css';

export function NavBar() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');

  const handleLogout = async () => {
    await logout();
    navigate('/');
  };

  // The only search capability that exists today is community search (GET /r/search) — sitewide post/user
  // search is backend feature 7, still parked. This has been a disabled placeholder since F1.
  const handleSearch = (e: FormEvent) => {
    e.preventDefault();
    navigate(query.trim() ? `/communities?q=${encodeURIComponent(query.trim())}` : '/communities');
  };

  return (
    <header className={styles.nav}>
      <Link to="/" className={styles.logoLink}>
        <Logo />
      </Link>

      <form className={styles.search} onSubmit={handleSearch}>
        <input
          className={styles.searchInput}
          type="search"
          placeholder="Search reddit"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </form>

      <div className={styles.actions}>
        {user ? (
          <div className={styles.userMenu}>
            <CreateMenu />
            <NotificationBell />
            <Link to={`/user/${user.username}`} className={styles.profileLink}>
              <span className={styles.avatar}>{user.username.slice(0, 1)}</span>
              <span className={styles.username}>{user.username}</span>
            </Link>
            <button type="button" className={styles.logoutButton} onClick={handleLogout}>
              Log Out
            </button>
          </div>
        ) : (
          <>
            <Link to="/login" className={styles.buttonSecondary}>
              Log In
            </Link>
            <Link to="/register" className={styles.buttonPrimary}>
              Sign Up
            </Link>
          </>
        )}
      </div>
    </header>
  );
}
