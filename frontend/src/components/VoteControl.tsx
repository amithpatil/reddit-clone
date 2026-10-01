import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import styles from './VoteControl.module.css';

interface VoteControlProps {
  score: number;
  myVote?: 1 | -1;
  onVote: (dir: 1 | -1) => void;
}

export function VoteControl({ score, myVote, onVote }: VoteControlProps) {
  const { user } = useAuth();
  const navigate = useNavigate();

  const handleClick = (dir: 1 | -1) => {
    if (!user) {
      navigate('/login');
      return;
    }
    onVote(dir);
  };

  const upClass = [styles.arrow, styles.arrowUp, myVote === 1 ? styles.active : ''].join(' ').trim();
  const downClass = [styles.arrow, styles.arrowDown, myVote === -1 ? styles.active : ''].join(' ').trim();

  return (
    <div className={styles.control}>
      <button type="button" className={upClass} aria-label="Upvote" onClick={() => handleClick(1)}>
        <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
          <path d="M10 4 L17 14 H3 Z" />
        </svg>
      </button>
      <span className={styles.score}>{score}</span>
      <button type="button" className={downClass} aria-label="Downvote" onClick={() => handleClick(-1)}>
        <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
          <path d="M10 16 L3 6 H17 Z" />
        </svg>
      </button>
    </div>
  );
}
