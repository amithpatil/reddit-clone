import { useAuth } from '../auth/AuthContext';
import styles from './Home.module.css';

// Placeholder route for F1 — confirms the shell renders and auth state reflects correctly.
// The real feed (sort tabs, post cards, voting) is F2's job.
export function Home() {
  const { user } = useAuth();

  return (
    <div className={styles.placeholder}>
      <h1>{user ? `Welcome back, ${user.username}` : 'Welcome to reddit'}</h1>
      <p>The home feed lands in F2. This page just proves the shell and auth session work.</p>
    </div>
  );
}
