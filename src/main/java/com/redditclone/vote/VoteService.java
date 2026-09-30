package com.redditclone.vote;

import com.redditclone.comment.CommentService;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.post.PostService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

@Service
public class VoteService {

    private final PostVoteRepository postVotes;
    private final CommentVoteRepository commentVotes;
    private final PostService postService;
    private final CommentService commentService;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public VoteService(PostVoteRepository postVotes, CommentVoteRepository commentVotes,
                        PostService postService, CommentService commentService,
                        UuidV7Generator ids, ObjectMapper json, JdbcTemplate jdbc) {
        this.postVotes = postVotes;
        this.commentVotes = commentVotes;
        this.postService = postService;
        this.commentService = commentService;
        this.ids = ids;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Transactional
    public void castPostVote(UUID userId, UUID postId, short direction) {
        requireDirection(direction);
        postService.findById(postId); // 404s on a nonexistent/removed post instead of creating an orphan vote
        lockVoteKey("post", userId, postId);
        Optional<PostVote> existing = postVotes.findById(new PostVoteId(userId, postId));
        if (existing.isPresent() && existing.get().getDirection() == direction) {
            return; // already voted this way — upsert would be a no-op, nothing for the worker to apply
        }
        Integer oldDirection = existing.map(v -> (int) v.getDirection()).orElse(null);
        postVotes.upsert(userId, postId, direction);
        writeOutboxEvent("post_vote_cast", new VoteEventPayload(postId, oldDirection, (int) direction));
    }

    @Transactional
    public void removePostVote(UUID userId, UUID postId) {
        lockVoteKey("post", userId, postId);
        PostVote existing = postVotes.findById(new PostVoteId(userId, postId))
                .orElseThrow(() -> new NotFoundException("vote not found"));
        postVotes.delete(existing); // unvoting deletes the row — no neutral "0" value
        writeOutboxEvent("post_vote_removed", new VoteEventPayload(postId, (int) existing.getDirection(), null));
    }

    @Transactional
    public void castCommentVote(UUID userId, UUID commentId, short direction) {
        requireDirection(direction);
        commentService.findById(commentId); // 404s on a nonexistent/removed comment instead of creating an orphan vote
        lockVoteKey("comment", userId, commentId);
        Optional<CommentVote> existing = commentVotes.findById(new CommentVoteId(userId, commentId));
        if (existing.isPresent() && existing.get().getDirection() == direction) {
            return;
        }
        Integer oldDirection = existing.map(v -> (int) v.getDirection()).orElse(null);
        commentVotes.upsert(userId, commentId, direction);
        writeOutboxEvent("comment_vote_cast", new VoteEventPayload(commentId, oldDirection, (int) direction));
    }

    @Transactional
    public void removeCommentVote(UUID userId, UUID commentId) {
        lockVoteKey("comment", userId, commentId);
        CommentVote existing = commentVotes.findById(new CommentVoteId(userId, commentId))
                .orElseThrow(() -> new NotFoundException("vote not found"));
        commentVotes.delete(existing);
        writeOutboxEvent("comment_vote_removed", new VoteEventPayload(commentId, (int) existing.getDirection(), null));
    }

    private void requireDirection(short direction) {
        if (direction != 1 && direction != -1) {
            throw new BadRequestException("dir must be 1 or -1");
        }
    }

    // Postgres defaults to READ COMMITTED, and there's no existing row to SELECT ... FOR UPDATE on a
    // user's first-ever vote, so a plain findById-then-write is a TOCTOU race: two concurrent requests for
    // the same (userId, targetId) can both read "no vote yet" before either commits, and both would then
    // write an outbox event double-applying the score/karma delta. A transaction-scoped advisory lock
    // serializes concurrent calls for the same key (auto-released at commit/rollback) without needing a
    // row to lock, closing the race with no schema change.
    private void lockVoteKey(String kind, UUID userId, UUID targetId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, kind + ":" + userId + ":" + targetId);
    }

    private void writeOutboxEvent(String type, VoteEventPayload payload) {
        // Plain INSERT via JdbcTemplate rather than OutboxEventRepository.save(): OutboxEvent's id is a
        // manually-assigned UUID with no @GeneratedValue, so a JPA save() on it resolves to merge(),
        // issuing a spurious SELECT-by-PK before the insert on the hottest endpoint in the app.
        String payloadJson = json.writeValueAsString(payload); // Jackson 3: JacksonException is unchecked
        jdbc.update("INSERT INTO outbox_events (id, event_type, payload) VALUES (?, ?, ?::jsonb)",
                ids.nextId(), type, payloadJson);
    }
}
