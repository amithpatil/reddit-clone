-- Tracks when a media row entered processing_status='processing' so a reaper job can detect and recover
-- rows left stuck there by a crash/restart mid-transcode (claimUploadedBatch's WHERE processing_status =
-- 'uploaded' filter never re-selects a row already flipped to 'processing', so without this timestamp
-- there's no way to distinguish "just claimed, still working" from "claimed before a crash, abandoned").
ALTER TABLE media ADD COLUMN processing_started_at TIMESTAMPTZ;
