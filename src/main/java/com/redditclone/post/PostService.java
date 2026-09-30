package com.redditclone.post;

import com.redditclone.auth.AuthService;
import com.redditclone.common.KarmaEvent;
import com.redditclone.common.RankFormulas;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.VoteDelta;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
import com.redditclone.community.CommunityService;
import com.redditclone.media.Media;
import com.redditclone.media.MediaService;
import com.redditclone.media.MediaView;
import com.redditclone.post.dto.CreatePostRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class PostService {

    private final PostRepository posts;
    private final UuidV7Generator ids;
    private final StringRedisTemplate redis;
    private final Sanitizer sanitizer;
    private final NamedParameterJdbcTemplate jdbc;
    private final CommunityService communityService;
    private final AuthService authService;
    private final MediaService mediaService;

    public PostService(PostRepository posts, UuidV7Generator ids, StringRedisTemplate redis, Sanitizer sanitizer,
                        NamedParameterJdbcTemplate jdbc, CommunityService communityService, AuthService authService,
                        MediaService mediaService) {
        this.posts = posts;
        this.ids = ids;
        this.redis = redis;
        this.sanitizer = sanitizer;
        this.jdbc = jdbc;
        this.communityService = communityService;
        this.authService = authService;
        this.mediaService = mediaService;
    }

    @Transactional
    public Post create(UUID authorId, UUID communityId, CreatePostRequest req, String idempotencyKey) {
        String key = "idempotency:" + authorId + ":" + idempotencyKey;
        UUID newId = ids.nextId();
        // Claim the key BEFORE inserting (SETNX-first), not after: a GET-then-insert-then-SETNX order
        // leaves a window where two concurrent requests both see no existing key and both insert a row.
        Boolean claimed = redis.opsForValue().setIfAbsent(key, newId.toString(), Duration.ofHours(24));
        if (!Boolean.TRUE.equals(claimed)) {
            String existingPostId = redis.opsForValue().get(key);
            return attachMedia(posts.findById(UUID.fromString(existingPostId))
                    .orElseThrow(() -> new NotFoundException("post not found")));
        }
        try {
            communityService.requireNotBanned(authorId, communityId);
            // Never trust a client-supplied mediaId without checking it resolves to something real, owned
            // by the caller, and actually usable — same principle already applied to vote targets. Runs
            // whenever mediaId is present, regardless of kind (a text/link post with a mediaId is exactly
            // as untrusted as an image/video one), and expectedKind=req.kind() rejects a mismatch between
            // the media's real type and what the post claims to be — including any text/link kind, since
            // Media.mediaType is never anything but "image"/"video". Placed here, inside the try block, so
            // a rejection releases the idempotency key above instead of permanently poisoning it.
            Media validatedMedia = null;
            if (req.mediaId() != null) {
                validatedMedia = mediaService.requireOwnedAndUsable(req.mediaId(), authorId, req.kind());
            }
            String title = sanitizer.sanitize(req.title());
            String body = sanitizer.sanitize(req.body());
            Post p = new Post();
            p.setId(newId);
            p.setCommunityId(communityId);
            p.setAuthorId(authorId);
            p.setKind(req.kind());
            p.setTitle(title);
            p.setBody(body);
            p.setUrl(req.url());
            p.setMediaId(req.mediaId());
            // Unlike controversial_rank/rising_rank, hot_rank's formula isn't 0 at zero votes (it also
            // carries a time term) — without this, every new post sits at the column default of 0 until its
            // first vote, sorting below any post that's ever been voted on, regardless of how new it is.
            p.setHotRank(RankFormulas.hotRank(0, p.getCreatedAt()));
            // Evaluated after the id is assigned but before save(), so a "remove" verdict is reflected in the
            // very first row written (never a visible-then-removed flash) and the audit/report rows automod
            // writes can reference a real, already-decided target id.
            int authorKarma = authService.getKarmaPost(authorId);
            if (communityService.evaluateAutomod(communityId, "post", newId, title, body, authorKarma)) {
                p.setRemoved(true);
            }
            posts.save(p);
            if (validatedMedia != null) {
                // Reuse the row requireOwnedAndUsable already fetched instead of a second findAllById
                // round trip a moment later via attachMedia() for a row that can't have changed since.
                p.setMedia(mediaService.toMediaView(validatedMedia));
                return p;
            }
            return attachMedia(p);
        } catch (RuntimeException e) {
            // The Redis claim above is outside this method's @Transactional boundary, so rolling back the
            // DB insert (e.g. on a ForbiddenException from requireNotBanned) doesn't undo it — release the
            // key so it doesn't point at a post that was never created, which would otherwise block any
            // retry with the same Idempotency-Key for 24h behind a misleading 404.
            redis.delete(key);
            throw e;
        }
    }

    public List<Post> findNewPage(UUID communityId, Instant cursorCreatedAt, UUID cursorId, UUID viewerId, int limit) {
        return attachMedia(posts.findNewPage(communityId, cursorCreatedAt, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    // Deliberately does NOT attach media — used internally by other services (ban checks, comment-reply's
    // post lookup, moderation target checks) that don't display the post and shouldn't pay for an extra
    // query they don't need. findByIdWithMedia below is for the display path.
    public Post findById(UUID postId) {
        return posts.findById(postId).orElseThrow(() -> new NotFoundException("post not found"));
    }

    public Post findByIdWithMedia(UUID postId) {
        return attachMedia(findById(postId));
    }

    // For ModerationService's human-initiated removal path — Post already has a public `removed` setter
    // (unlike score/hot_rank/etc, it was never migrated to the raw-SQL-bulk-only pattern).
    @Transactional
    public void markRemoved(UUID postId) {
        Post p = findById(postId);
        p.setRemoved(true);
        posts.save(p);
    }

    // No pagination — a relevance ranking (ts_rank) isn't a stable keyset sort key the way created_at/
    // score are, so this returns a single page, same as the source plan's own search sketch.
    public List<Post> search(UUID communityId, String query) {
        List<UUID> rankedIds = posts.searchIds(communityId, query);
        if (rankedIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Post> byId = new HashMap<>();
        posts.findAllById(rankedIds).forEach(p -> byId.put(p.getId(), p));
        return attachMedia(rankedIds.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    public void incrementCommentCount(UUID postId) {
        posts.incrementCommentCount(postId);
    }

    public List<Post> findHotPage(UUID communityId, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachMedia(posts.findHotPage(communityId, cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findTopPage(UUID communityId, Instant since, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachMedia(posts.findTopPage(communityId, since, (int) cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findRisingPage(UUID communityId, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachMedia(posts.findRisingPage(communityId, cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findControversialPage(UUID communityId, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachMedia(posts.findControversialPage(communityId, cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    // Single batched IN query, never N+1 — called at the end of every page-returning method above (plus
    // create()'s single-post return) rather than from PostController, so the /hot cache path is
    // automatically correct: media is attached before the listing is serialized and cached.
    private List<Post> attachMedia(List<Post> page) {
        Set<UUID> mediaIds = new HashSet<>();
        for (Post p : page) {
            if (p.getMediaId() != null) {
                mediaIds.add(p.getMediaId());
            }
        }
        if (mediaIds.isEmpty()) {
            return page;
        }
        Map<UUID, MediaView> views = mediaService.getMediaViews(mediaIds);
        for (Post p : page) {
            if (p.getMediaId() != null) {
                p.setMedia(views.get(p.getMediaId()));
            }
        }
        return page;
    }

    private Post attachMedia(Post p) {
        if (p.getMediaId() != null) {
            p.setMedia(mediaService.getMediaViews(Set.of(p.getMediaId())).get(p.getMediaId()));
        }
        return p;
    }

    // Applies a batch of grouped vote deltas (one entry per post touched, not per vote — see
    // OutboxWorker) via raw SQL bulk updates rather than loading Post entities, same reasoning as
    // CommentService.applyVoteDeltas. Also recomputes hot_rank/controversial_rank/rising_rank here,
    // right after the score/ups/downs they depend on change, so a feed read is always a plain indexed
    // ORDER BY and never a runtime calculation (see the source plan's Search & feed caching section).
    // Returns one KarmaEvent per post whose net score actually changed.
    @Transactional
    public List<KarmaEvent> applyVoteDeltas(Map<UUID, VoteDelta> deltas) {
        if (deltas.isEmpty()) {
            return List.of();
        }
        SqlParameterSource[] deltaParams = deltas.entrySet().stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("id", e.getKey())
                        .addValue("score", e.getValue().scoreDelta())
                        .addValue("ups", e.getValue().upsDelta())
                        .addValue("downs", e.getValue().downsDelta()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                UPDATE posts SET score = score + :score, ups = ups + :ups, downs = downs + :downs
                WHERE id = :id
                """, deltaParams);

        Set<UUID> ids = deltas.keySet();
        List<Map<String, Object>> fresh = jdbc.queryForList("""
                SELECT id, author_id, score, ups, downs, created_at, rising_updated_at
                FROM posts WHERE id IN (:ids)
                """, new MapSqlParameterSource("ids", ids));

        Instant now = Instant.now();
        List<SqlParameterSource> rankParams = new ArrayList<>();
        List<KarmaEvent> karmaEvents = new ArrayList<>();
        for (Map<String, Object> row : fresh) {
            UUID id = (UUID) row.get("id");
            UUID authorId = (UUID) row.get("author_id");
            int score = (Integer) row.get("score");
            int ups = (Integer) row.get("ups");
            int downs = (Integer) row.get("downs");
            Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();
            Instant risingUpdatedAt = ((Timestamp) row.get("rising_updated_at")).toInstant();

            double hotRank = RankFormulas.hotRank(score, createdAt);
            double controversialRank = RankFormulas.controversialRank(ups, downs);

            // "Rising" is this project's own definition (the source plan leaves it undefined): recent
            // vote velocity, measured as this batch's net vote-magnitude (|upsDelta| + |downsDelta|) over
            // the time since this post's rank was last touched (or since it was created, for its
            // first-ever vote). Using the net magnitude rather than a raw per-event count means a vote
            // immediately canceled by an unvote (net zero ups/downs change) contributes nothing — vote
            // churn can't inflate rising_rank with no real engagement behind it. A burst of votes spikes
            // rising_rank; RankDecayJob halves it periodically so silence lets it fade, since this value
            // is only ever recomputed here, on a vote, not continuously.
            VoteDelta delta = deltas.get(id);
            int voteMagnitude = Math.abs(delta.upsDelta()) + Math.abs(delta.downsDelta());
            double elapsedMinutes = Math.max(Duration.between(risingUpdatedAt, now).toSeconds() / 60.0, 1.0 / 60);
            double risingRank = voteMagnitude / elapsedMinutes;

            rankParams.add(new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("hotRank", hotRank)
                    .addValue("controversialRank", controversialRank)
                    .addValue("risingRank", risingRank));

            int scoreDelta = delta.scoreDelta();
            if (scoreDelta != 0) {
                karmaEvents.add(new KarmaEvent(authorId, scoreDelta, id));
            }
        }
        jdbc.batchUpdate("""
                UPDATE posts SET hot_rank = :hotRank, controversial_rank = :controversialRank,
                                  rising_rank = :risingRank, rising_updated_at = now()
                WHERE id = :id
                """, rankParams.toArray(new SqlParameterSource[0]));
        return karmaEvents;
    }
}
