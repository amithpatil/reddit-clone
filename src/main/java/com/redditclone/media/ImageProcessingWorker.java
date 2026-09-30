package com.redditclone.media;

import net.coobird.thumbnailator.Thumbnails;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

// Image resizing is milliseconds, so this processes inline on the @Scheduled thread rather than needing
// its own bounded pool the way VideoProcessingWorker's ffmpeg calls do.
@Component
public class ImageProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingWorker.class);
    private static final int BATCH_SIZE = 10;
    private static final int THUMBNAIL_MAX_DIMENSION = 256;
    private static final int DISPLAY_MAX_DIMENSION = 1280;

    private final MediaService mediaService;
    private final StorageService storage;

    public ImageProcessingWorker(MediaService mediaService, StorageService storage) {
        this.mediaService = mediaService;
        this.storage = storage;
    }

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "imageProcessingWorker", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    public void processBatch() {
        List<ClaimedMedia> batch = mediaService.claimUploadedBatch("image", BATCH_SIZE);
        for (ClaimedMedia claimed : batch) {
            try {
                process(claimed);
            } catch (Exception e) {
                log.warn("image processing failed for media {}: {}", claimed.id(), e.getMessage());
                mediaService.markFailedOrRetry(claimed.id(), e.getMessage());
            }
        }
    }

    private void process(ClaimedMedia claimed) throws IOException {
        byte[] original = storage.get(claimed.r2Key());
        // Real format sniffed from magic bytes via ImageIO's own decoding, never trusted from the
        // client's declared content_type — a client can lie about it.
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
        if (image == null) {
            throw new IOException("not a decodable image");
        }
        Resized thumbnail = resize(image, THUMBNAIL_MAX_DIMENSION);
        Resized display = resize(image, DISPLAY_MAX_DIMENSION);

        String thumbnailKey = claimed.r2Key() + "-thumb.jpg";
        String displayKey = claimed.r2Key() + "-display.jpg";
        storage.put(thumbnailKey, thumbnail.bytes(), "image/jpeg");
        storage.put(displayKey, display.bytes(), "image/jpeg");

        // width/height reported to the client are the *display* variant's actual post-resize dimensions
        // (what the client will render), not the original upload's.
        mediaService.markReady(claimed.id(), thumbnailKey, displayKey, display.width(), display.height(), null);
    }

    private Resized resize(BufferedImage image, int maxDimension) throws IOException {
        BufferedImage resized = Thumbnails.of(image).size(maxDimension, maxDimension).asBufferedImage();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(resized, "jpg", out);
        return new Resized(out.toByteArray(), resized.getWidth(), resized.getHeight());
    }

    private record Resized(byte[] bytes, int width, int height) {
    }
}
