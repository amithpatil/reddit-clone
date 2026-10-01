ALTER TABLE user_settings ADD COLUMN notification_prefs JSONB NOT NULL DEFAULT '{}';
