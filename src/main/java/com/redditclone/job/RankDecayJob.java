package com.redditclone.job;

import com.redditclone.common.correlation.CorrelationIdFilter;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// This project's own addition, not from the source plan's literal code sketch: hot_rank doesn't need a
// periodic recompute (its time term grows at the same rate for every row, so relative order among
// undisturbed posts never changes — only a new vote, handled inline by PostService.applyVoteDeltas,
// needs to touch it), and top/controversial are computed straight from ups/downs, which likewise only
// change on a vote. rising_rank is different: it's a velocity reading ("votes per minute right now",
// see PostService.applyVoteDeltas), which goes stale the moment voting on a post slows down — without
// something to bring it back down, a post that got a burst of votes hours ago stays "rising" forever.
// This periodically halves it, so a post can only actually rank on Rising while it keeps getting votes.
@Component
public class RankDecayJob {

    private final JdbcTemplate jdbc;

    public RankDecayJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    @SchedulerLock(name = "rankDecayJob", lockAtLeastFor = "10s", lockAtMostFor = "4m")
    @Transactional
    public void decayRising() {
        // Not tied to any request; wrapped purely so this thread's MDC state is sane for whatever job
        // runs next — see ReconciliationJob's identical comment (all @Scheduled jobs in this app share one
        // default thread).
        MDC.put(CorrelationIdFilter.MDC_KEY, "job-" + UUID.randomUUID());
        try {
            // rising_rank always decays to exactly 0 within about an hour of no new votes (halved every 5
            // minutes here, floored below 0.01), so a post untouched for 24h+ can never legitimately still
            // have rising_rank > 0 — this bound doesn't change which rows match, it just lets the new
            // posts_rising_active_idx (V10) turn this from a full-table scan into a bounded range scan.
            jdbc.update("""
                    UPDATE posts
                    SET rising_rank = CASE WHEN rising_rank * 0.5 < 0.01 THEN 0 ELSE rising_rank * 0.5 END
                    WHERE rising_rank > 0 AND NOT removed AND rising_updated_at > now() - interval '24 hours'
                    """);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
