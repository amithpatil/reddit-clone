package com.redditclone.post;

import com.redditclone.common.KarmaEvent;
import com.redditclone.common.RankFormulas;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.VoteDelta;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PostService {

    private final PostRepository posts;
    private final UuidV7Generator ids;
    private final StringRedisTemplate redis;
    private final Sanitizer sanitizer;
    private final NamedParameterJdbcTemplate jdbc;

    public PostService(PostRepository posts, UuidV7Generator ids, StringRedisTemplate redis, Sanitizer sanitizer,
                        NamedParameterJdbcTemplate jdbc) {
        this.posts = posts;
        this.ids = ids;
        this.redis = redis;
        this.sanitizer = sanitizer;
        this.jdbc = jdbc;
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
            return posts.findById(UUID.fromString(existingPostId))
                    .orElseThrow(() -> new NotFoundException("post not found"));
        }
        Post p = new Post();
        p.setId(newId);
        p.setCommunityId(communityId);
        p.setAuthorId(authorId);
        p.setKind(req.kind());
        p.setTitle(sanitizer.sanitize(req.title()));
        p.setBody(sanitizer.sanitize(req.body()));
        p.setUrl(req.url());
        // Unlike controversial_rank/rising_rank, hot_rank's formula isn't 0 at zero votes (it also
        // carries a time term) — without this, every new post sits at the column default of 0 until its
        // first vote, sorting below any post that's ever been voted on, regardless of how new it is.
        p.setHotRank(RankFormulas.hotRank(0, p.getCreatedAt()));
        posts.save(p);
        return p;
    }

    public List<Post> findNewPage(UUID communityId, Instant cursorCreatedAt, UUID cursorId, int limit) {
        return posts.findNewPage(communityId, cursorCreatedAt, cursorId, Pageable.ofSize(limit));
    }

    public Post findById(UUID postId) {
        return posts.findById(postId).orElseThrow(() -> new NotFoundException("post not found"));
    }

    public void incrementCommentCount(UUID postId) {
        posts.incrementCommentCount(postId);
    }

    public List<Post> findHotPage(UUID communityId, double cursorRank, UUID cursorId, int limit) {
        return posts.findHotPage(communityId, cursorRank, cursorId, Pageable.ofSize(limit));
    }

    public List<Post> findTopPage(UUID communityId, Instant since, double cursorRank, UUID cursorId, int limit) {
        return posts.findTopPage(communityId, since, (int) cursorRank, cursorId, Pageable.ofSize(limit));
    }

    public List<Post> findRisingPage(UUID communityId, double cursorRank, UUID cursorId, int limit) {
        return posts.findRisingPage(communityId, cursorRank, cursorId, Pageable.ofSize(limit));
    }

    public List<Post> findControversialPage(UUID communityId, double cursorRank, UUID cursorId, int limit) {
        return posts.findControversialPage(communityId, cursorRank, cursorId, Pageable.ofSize(limit));
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
