package com.redditclone.media;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Reclaims media rows left stuck in processing_status='processing' after a crash/restart mid-transcode —
// see MediaService.reapStaleProcessing for why this is needed. Mirrors this codebase's existing
// ReconciliationJob/RankDecayJob pattern (a periodic, ShedLock-coordinated integrity sweep).
@Component
public class MediaReaperJob {

    private static final Logger log = LoggerFactory.getLogger(MediaReaperJob.class);
    private static final int STALE_AFTER_MINUTES = 10;

    private final MediaService mediaService;

    public MediaReaperJob(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Scheduled(fixedDelay = 300000) // 5 minutes
    @SchedulerLock(name = "mediaReaperJob", lockAtLeastFor = "10s", lockAtMostFor = "1m")
    public void reap() {
        int reaped = mediaService.reapStaleProcessing(STALE_AFTER_MINUTES);
        if (reaped > 0) {
            log.warn("reaped {} media row(s) stuck in 'processing' (crash/restart mid-job) back to 'uploaded' or 'failed'", reaped);
        }
    }
}
