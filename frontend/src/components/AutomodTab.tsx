import { useState, type FormEvent } from 'react';
import { useAutomodRules } from '../hooks/useAutomodRules';
import type { AutomodAction, AutomodRule, AutomodRuleType } from '../types/moderation';
import styles from './AutomodTab.module.css';

interface AutomodTabProps {
  communityName: string;
  canManage: boolean;
}

function describeConfig(rule: AutomodRule): string {
  switch (rule.ruleType) {
    case 'keyword':
      return 'keywords' in rule.config ? rule.config.keywords.join(', ') : '';
    case 'regex':
      return 'pattern' in rule.config ? rule.config.pattern : '';
    case 'karma_threshold':
      return 'minKarma' in rule.config ? `min karma: ${rule.config.minKarma}` : '';
    default:
      return '';
  }
}

export function AutomodTab({ communityName, canManage }: AutomodTabProps) {
  const { rules, loading, error, addRule, removeRule } = useAutomodRules(communityName);
  const [ruleType, setRuleType] = useState<AutomodRuleType>('keyword');
  const [action, setAction] = useState<AutomodAction>('report');
  const [keywordsInput, setKeywordsInput] = useState('');
  const [patternInput, setPatternInput] = useState('');
  const [minKarmaInput, setMinKarmaInput] = useState('0');
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setSubmitting(true);
    try {
      if (ruleType === 'keyword') {
        const keywords = keywordsInput.split(',').map((k) => k.trim()).filter(Boolean);
        if (keywords.length === 0) throw new Error('Enter at least one keyword.');
        await addRule('keyword', { keywords }, action);
        setKeywordsInput('');
      } else if (ruleType === 'regex') {
        if (!patternInput.trim()) throw new Error('Enter a regex pattern.');
        await addRule('regex', { pattern: patternInput.trim() }, action);
        setPatternInput('');
      } else {
        const minKarma = Number(minKarmaInput);
        if (!Number.isFinite(minKarma)) throw new Error('Enter a valid number.');
        await addRule('karma_threshold', { minKarma }, action);
        setMinKarmaInput('0');
      }
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not add that rule.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      {canManage && (
        <form className={styles.form} onSubmit={handleSubmit}>
          <div className={styles.formRow}>
            <select className={styles.select} value={ruleType} onChange={(e) => setRuleType(e.target.value as AutomodRuleType)}>
              <option value="keyword">Keyword match</option>
              <option value="regex">Regex match</option>
              <option value="karma_threshold">Karma threshold</option>
            </select>
            <select className={styles.select} value={action} onChange={(e) => setAction(e.target.value as AutomodAction)}>
              <option value="report">Report</option>
              <option value="remove">Remove</option>
            </select>
          </div>
          {ruleType === 'keyword' && (
            <input
              className={styles.input}
              placeholder="Comma-separated keywords (e.g. spam, scam)"
              value={keywordsInput}
              onChange={(e) => setKeywordsInput(e.target.value)}
            />
          )}
          {ruleType === 'regex' && (
            <input
              className={styles.input}
              placeholder="Regex pattern (e.g. ^spam.*)"
              value={patternInput}
              onChange={(e) => setPatternInput(e.target.value)}
            />
          )}
          {ruleType === 'karma_threshold' && (
            <input
              className={styles.input}
              type="number"
              placeholder="Minimum karma"
              value={minKarmaInput}
              onChange={(e) => setMinKarmaInput(e.target.value)}
            />
          )}
          <button type="submit" className={styles.submitButton} disabled={submitting}>
            {submitting ? 'Adding…' : 'Add rule'}
          </button>
          {formError && <p className={styles.formError}>{formError}</p>}
        </form>
      )}

      {loading ? (
        <div className={styles.state}>Loading…</div>
      ) : error ? (
        <div className={styles.state}>{error}</div>
      ) : rules.length === 0 ? (
        <div className={styles.state}>No automod rules yet.</div>
      ) : (
        rules.map((rule) => (
          <div key={rule.id} className={styles.row}>
            <div>
              <span className={styles.badge}>{rule.ruleType}</span>
              <span className={styles.actionBadge}>{rule.action}</span>
              <div className={styles.config}>{describeConfig(rule)}</div>
            </div>
            {canManage && (
              <button type="button" className={styles.deleteButton} onClick={() => removeRule(rule.id)}>
                Delete
              </button>
            )}
          </div>
        ))
      )}
    </div>
  );
}
