package com.redditclone.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Optional;

// Thin wrapper around the S3 client/presigner — keeps every other class in this module ignorant of the
// AWS SDK's own types. Works identically against s3mock (local dev) and Cloudflare R2 (prod).
@Component
public class StorageService {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public StorageService(S3Client s3, S3Presigner presigner, @Value("${app.storage.bucket}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    public String presignPut(String key, String contentType, Duration expiry) {
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(b -> b.bucket(bucket).key(key).contentType(contentType))
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    // Confirms the object genuinely exists before a completeUpload() call flips a media row eligible for
    // processing — defends against a client claiming "done" without ever having PUT the bytes.
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // Not every S3-compatible server returns the exact NoSuchKey error code on a 404 — fall back
            // to the raw status code rather than assuming AWS's own error taxonomy everywhere.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    public byte[] get(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    public void put(String key, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    // Single HEAD request serving both existence and actual size — completeUpload() uses this to verify
    // what was really PUT, not just that something is there, since the client's earlier declared byteSize
    // is never otherwise checked against reality.
    public Optional<Long> headObjectContentLength(String key) {
        try {
            return Optional.of(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // Idempotent — called by MediaBucketBootstrap on every startup. Convenient for local dev (s3mock
    // already pre-creates the bucket via its own env var, so this is a harmless no-op there); in prod the
    // R2 bucket is created once out-of-band, not on every boot, but this still makes a fresh environment
    // self-sufficient. Only a genuine "bucket doesn't exist" signal triggers createBucket — anything else
    // (a connectivity blip while s3mock is still starting, a 403 from bad credentials) propagates instead
    // of being masked as "let's just try to create it," which would otherwise throw its own confusing,
    // uncaught error and fail application startup outright.
    public void createBucketIfMissing() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                s3.createBucket(b -> b.bucket(bucket));
            } else {
                throw e;
            }
        }
    }
}
