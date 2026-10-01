import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Logo } from './Logo';
import styles from './NavBar.module.css';

export function NavBar() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/');
  };

  return (
    <header className={styles.nav}>
      <Link to="/" className={styles.logoLink}>
        <Logo />
      </Link>

      <div className={styles.search}>
        <input className={styles.searchInput} type="search" placeholder="Search reddit" disabled />
      </div>

      <div className={styles.actions}>
        {user ? (
          <div className={styles.userMenu}>
            <span className={styles.avatar}>{user.username.slice(0, 1)}</span>
            <span className={styles.username}>{user.username}</span>
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
