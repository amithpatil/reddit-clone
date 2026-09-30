package com.redditclone.comment;

import com.redditclone.common.KarmaEvent;
import com.redditclone.common.RankFormulas;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.VoteDelta;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
import com.redditclone.post.PostService;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CommentService {

    private static final int MAX_DEPTH = 10;
    private static final int TOP_LEVEL_PAGE_SIZE = 50;

    private final CommentRepository comments;
    private final PostService postService;
    private final UuidV7Generator ids;
    private final Sanitizer sanitizer;
    private final NamedParameterJdbcTemplate jdbc;

    public CommentService(CommentRepository comments, PostService postService, UuidV7Generator ids,
                           Sanitizer sanitizer, NamedParameterJdbcTemplate jdbc) {
        this.comments = comments;
        this.postService = postService;
        this.ids = ids;
        this.sanitizer = sanitizer;
        this.jdbc = jdbc;
    }

    @Transactional
    public Comment reply(UUID authorId, UUID postId, UUID parentId, String body) {
        postService.findById(postId); // 404s on a nonexistent/deleted post instead of creating an orphan
        Comment c = new Comment();
        c.setId(ids.nextId());
        c.setPostId(postId);
        c.setParentId(parentId);
        c.setAuthorId(authorId);
        c.setBody(sanitizer.sanitize(body));

        if (parentId == null) {
            c.setDepth((short) 0);
            c.setPath(toLabel(c.getId()));
        } else {
            Comment parent = comments.findById(parentId)
                    .orElseThrow(() -> new NotFoundException("parent comment not found"));
            if (!parent.getPostId().equals(postId)) {
                throw new BadRequestException("parent comment does not belong to this post");
            }
            if (parent.getDepth() >= MAX_DEPTH) {
                throw new BadRequestException("max comment depth reached");
            }
            c.setDepth((short) (parent.getDepth() + 1));
            c.setPath(parent.getPath() + "." + toLabel(c.getId()));
            comments.incrementChildCount(parentId);
        }
        Comment saved = comments.save(c);
        postService.incrementCommentCount(postId);
        return saved;
    }

    public List<Comment> findTopLevel(UUID postId) {
        return comments.findTopLevel(postId, Pageable.ofSize(TOP_LEVEL_PAGE_SIZE));
    }

    public Comment findById(UUID commentId) {
        return comments.findById(commentId).orElseThrow(() -> new NotFoundException("comment not found"));
    }

    // Applies a batch of grouped vote deltas (one entry per comment touched, not per vote — see
    // OutboxWorker) via raw SQL bulk updates rather than loading Comment entities: avoids the same
    // stale-persistence-context class of bug incrementChildCount's clearAutomatically works around, and
    // matches the plan's "one grouped UPDATE per target, not one per vote" design goal. Returns one
    // KarmaEvent per comment whose net score actually changed, for the caller to attribute to karma_log
    // and users.karma_comment — this module never touches those tables itself (see ModuleBoundaryTest).
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
                UPDATE comments SET score = score + :score, ups = ups + :ups, downs = downs + :downs
                WHERE id = :id
                """, deltaParams);

        Set<UUID> ids = deltas.keySet();
        List<Map<String, Object>> fresh = jdbc.queryForList(
                "SELECT id, author_id, ups, downs FROM comments WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids));

        List<SqlParameterSource> rankParams = new ArrayList<>();
        List<KarmaEvent> karmaEvents = new ArrayList<>();
        for (Map<String, Object> row : fresh) {
            UUID id = (UUID) row.get("id");
            UUID authorId = (UUID) row.get("author_id");
            int ups = (Integer) row.get("ups");
            int downs = (Integer) row.get("downs");
            double bestRank = RankFormulas.bestRank(ups, downs);
            rankParams.add(new MapSqlParameterSource().addValue("id", id).addValue("bestRank", bestRank));

            int scoreDelta = deltas.get(id).scoreDelta();
            if (scoreDelta != 0) {
                karmaEvents.add(new KarmaEvent(authorId, scoreDelta, id));
            }
        }
        jdbc.batchUpdate("UPDATE comments SET best_rank = :bestRank WHERE id = :id",
                rankParams.toArray(new SqlParameterSource[0]));
        return karmaEvents;
    }

    // ltree labels only allow [A-Za-z0-9_] — a UUID's hyphens aren't valid, so this strips them to a
    // plain 32-char hex label.
    private String toLabel(UUID id) {
        return id.toString().replace("-", "");
    }
}
