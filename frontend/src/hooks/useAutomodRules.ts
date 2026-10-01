import { useCallback, useEffect, useState } from 'react';
import { addAutomodRule, fetchAutomodRules, removeAutomodRule } from '../lib/moderationApi';
import type { AutomodAction, AutomodRule, AutomodRuleConfig, AutomodRuleType } from '../types/moderation';

interface UseAutomodRulesResult {
  rules: AutomodRule[];
  loading: boolean;
  error: string | null;
  addRule: (ruleType: AutomodRuleType, config: AutomodRuleConfig, action: AutomodAction) => Promise<void>;
  removeRule: (ruleId: string) => Promise<void>;
}

export function useAutomodRules(communityName: string): UseAutomodRulesResult {
  const [rules, setRules] = useState<AutomodRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setRules(await fetchAutomodRules(communityName));
    } catch {
      setError('Could not load automod rules.');
    } finally {
      setLoading(false);
    }
  }, [communityName]);

  useEffect(() => {
    load();
  }, [load]);

  const addRule = useCallback(
    async (ruleType: AutomodRuleType, config: AutomodRuleConfig, action: AutomodAction) => {
      const rule = await addAutomodRule(communityName, ruleType, config, action);
      setRules((prev) => [...prev, rule]);
    },
    [communityName],
  );

  const removeRule = useCallback(
    async (ruleId: string) => {
      await removeAutomodRule(communityName, ruleId);
      setRules((prev) => prev.filter((r) => r.id !== ruleId));
    },
    [communityName],
  );

  return { rules, loading, error, addRule, removeRule };
}
