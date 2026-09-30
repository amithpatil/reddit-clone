package com.redditclone.job;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Nightly integrity sweep for the tables that intentionally skip DB-level foreign keys (Data model —
// Resolved design decisions, "Foreign keys"): post_votes, comment_votes, comments.
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final JdbcTemplate jdbc;

    // MeterRegistry.gauge(name, Number) only registers a meter once per name and holds a weak reference
    // to the Number passed in — calling it again with a fresh boxed int/Long each run doesn't rebind the
    // gauge, so the exported value freezes at the first run's count (or goes NaN once that box is GC'd).
    // Backing the gauge with a field-level Atomic*, registered once and mutated in place, keeps it live.
    private final AtomicInteger orphanPostVotesGauge = new AtomicInteger();
    private final AtomicInteger orphanCommentVotesGauge = new AtomicInteger();
    private final AtomicLong orphanCommentsGauge = new AtomicLong();

    public ReconciliationJob(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        meterRegistry.gauge("reconciliation.orphan_post_votes_removed", orphanPostVotesGauge);
        meterRegistry.gauge("reconciliation.orphan_comment_votes_removed", orphanCommentVotesGauge);
        meterRegistry.gauge("reconciliation.orphan_comments_found", orphanCommentsGauge);
    }

    @Scheduled(cron = "0 30 3 * * *") // 03:30 server time, off-peak
    @SchedulerLock(name = "reconciliationJob", lockAtLeastFor = "1m", lockAtMostFor = "30m")
    @Transactional
    public void run() {
        // Vote rows pointing at a deleted target are safe to clean up outright — a vote has no content of its own.
        int orphanPostVotes = jdbc.update("""
                DELETE FROM post_votes pv WHERE NOT EXISTS (SELECT 1 FROM posts p WHERE p.id = pv.post_id)
                """);
        int orphanCommentVotes = jdbc.update("""
                DELETE FROM comment_votes cv WHERE NOT EXISTS (SELECT 1 FROM comments c WHERE c.id = cv.comment_id)
                """);
        // A comment pointing at a missing post is a real anomaly (it carries content and moderation history),
        // so this only counts and alerts — a human decides what happened, nothing is auto-deleted.
        Long orphanComments = jdbc.queryForObject("""
                SELECT count(*) FROM comments c WHERE NOT EXISTS (SELECT 1 FROM posts p WHERE p.id = c.post_id)
                """, Long.class);

        orphanPostVotesGauge.set(orphanPostVotes);
        orphanCommentVotesGauge.set(orphanCommentVotes);
        orphanCommentsGauge.set(orphanComments == null ? 0 : orphanComments);
        if (orphanComments != null && orphanComments > 0) {
            log.warn("reconciliation: {} comments reference a missing post — investigate before trusting the soft-delete invariant again", orphanComments);
        }
    }
}
