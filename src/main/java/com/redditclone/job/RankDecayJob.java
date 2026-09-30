package com.redditclone.job;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
        jdbc.update("""
                UPDATE posts
                SET rising_rank = CASE WHEN rising_rank * 0.5 < 0.01 THEN 0 ELSE rising_rank * 0.5 END
                WHERE rising_rank > 0 AND NOT removed
                """);
    }
}
