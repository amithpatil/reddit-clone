package com.redditclone.media;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Mirrors SystemAccountBootstrap's established idempotent-every-boot pattern from Phase 3. Convenient
// for local dev; in prod the R2 bucket is normally created once out-of-band, but this still makes a
// fresh environment self-sufficient rather than requiring a manual step.
@Component
public class MediaBucketBootstrap implements ApplicationRunner {

    private final StorageService storage;

    public MediaBucketBootstrap(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        storage.createBucketIfMissing();
    }
}
