package com.redditclone.vote;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

@Service
public class VoteService {

    private final PostVoteRepository postVotes;
    private final CommentVoteRepository commentVotes;
    private final OutboxEventRepository outbox;
    private final UuidV7Generator ids;
    private final ObjectMapper json;

    public VoteService(PostVoteRepository postVotes, CommentVoteRepository commentVotes,
                        OutboxEventRepository outbox, UuidV7Generator ids, ObjectMapper json) {
        this.postVotes = postVotes;
        this.commentVotes = commentVotes;
        this.outbox = outbox;
        this.ids = ids;
        this.json = json;
    }

    @Transactional
    public void castPostVote(UUID userId, UUID postId, short direction) {
        requireDirection(direction);
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
        PostVote existing = postVotes.findById(new PostVoteId(userId, postId))
                .orElseThrow(() -> new NotFoundException("vote not found"));
        postVotes.delete(existing); // unvoting deletes the row — no neutral "0" value
        writeOutboxEvent("post_vote_removed", new VoteEventPayload(postId, (int) existing.getDirection(), null));
    }

    @Transactional
    public void castCommentVote(UUID userId, UUID commentId, short direction) {
        requireDirection(direction);
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

    private void writeOutboxEvent(String type, VoteEventPayload payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(ids.nextId());
        event.setEventType(type);
        event.setPayload(json.writeValueAsString(payload)); // Jackson 3: JacksonException is unchecked
        outbox.save(event); // same transaction as the vote row: both commit together, or neither does
    }
}
