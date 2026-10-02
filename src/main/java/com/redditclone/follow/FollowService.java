package com.redditclone.follow;

import com.redditclone.auth.AuthService;
import com.redditclone.auth.User;
import com.redditclone.auth.dto.PublicProfile;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.Thing;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FollowService {

    // Reddit's own kind code for an account — this is the first cursor-paginated listing of users in the
    // app (GET /user/search has no pagination), so no shared kind constant existed to reuse.
    private static final String USER_KIND = "t2";
    private static final int PAGE_SIZE = 25;
    // Caps POST /user/follow-status's request body — unlike every other listing in this app, that endpoint
    // takes a client-supplied list with no natural page size, so without a cap it's an unbounded SQL IN
    // clause with no rate-limit coverage.
    private static final int MAX_BATCH_STATUS_SIZE = 100;

    private final FollowRepository follows;
    private final AuthService auth;
    private final OutboxWriter outbox;

    public FollowService(FollowRepository follows, AuthService auth, OutboxWriter outbox) {
        this.follows = follows;
        this.auth = auth;
        this.outbox = outbox;
    }

    // Returns whether this call actually changed the relationship (false for the idempotent already-
    // following case) — read by FollowController so the frontend can reconcile its optimistic follower
    // count against what really happened server-side, instead of always trusting its own pre-click guess
    // at the prior isFollowing state (which can be wrong, e.g. if the best-effort status fetch failed).
    @Transactional
    public boolean follow(UUID followerId, String targetUsername) {
        UUID targetId = auth.findUserIdByUsername(targetUsername)
                .orElseThrow(() -> new NotFoundException("no such user"));
        if (followerId.equals(targetId)) {
            throw new BadRequestException("cannot follow yourself");
        }
        // Atomic insert-if-absent rather than exists-check-then-save — see FollowRepository.insertIfAbsent.
        if (follows.insertIfAbsent(followerId, targetId, Instant.now()) == 0) {
            return false; // already following, idempotent
        }
        auth.adjustFollowerCount(targetId, 1);
        auth.adjustFollowingCount(followerId, 1);
        outbox.writeEvent("notification", Map.of(
                "userId", targetId, "type", "new_follower", "source", Map.of("actorId", followerId)));
        return true;
    }

    @Transactional
    public boolean unfollow(UUID followerId, String targetUsername) {
        UUID targetId = auth.findUserIdByUsername(targetUsername)
                .orElseThrow(() -> new NotFoundException("no such user"));
        if (follows.deleteByFollowerIdAndFolloweeId(followerId, targetId) > 0) {
            auth.adjustFollowerCount(targetId, -1);
            auth.adjustFollowingCount(followerId, -1);
            return true;
        }
        return false;
    }

    // Single-item convenience, same role as CommunityService.attachViewerContext — read by
    // FollowController.followStatus, an authenticated-only endpoint, so viewerId is always a real caller.
    public boolean isFollowing(UUID viewerId, String targetUsername) {
        UUID targetId = auth.findUserIdByUsername(targetUsername)
                .orElseThrow(() -> new NotFoundException("no such user"));
        return follows.existsByFollowerIdAndFolloweeId(viewerId, targetId);
    }

    // Batched isFollowing resolution for a page of results — one IN-query regardless of page size, same
    // shape as MembershipRepository.findByUserIdAndCommunityIdIn's role in
    // CommunityService.attachViewerContextBatch. Unlike Community (a mutable entity), PublicProfile is an
    // immutable record, so this set is resolved and consulted *before* each PublicProfile is constructed
    // rather than attached onto it afterward.
    public Set<UUID> findFolloweeIdsAmong(UUID viewerId, Collection<UUID> candidateIds) {
        if (viewerId == null || candidateIds.isEmpty()) {
            return Set.of();
        }
        return follows.findByFollowerIdAndFolloweeIdIn(viewerId, candidateIds).stream()
                .map(Follow::getFolloweeId).collect(Collectors.toSet());
    }

    // Batch status check for a page of search results — auth.UserController.search() can't attach
    // isFollowing itself (auth has no dependency on follow, see ModuleBoundaryTest's cycle-freedom rule), so
    // the frontend calls this separately with the usernames from that response. Only ever called for a
    // logged-in viewer (the endpoint is authenticated-only); an unknown username is silently omitted from
    // the result rather than erroring, since a stale/racing username is not this endpoint's concern.
    public Map<String, Boolean> findFollowingStatusByUsernames(UUID viewerId, Collection<String> usernames) {
        if (usernames.isEmpty()) {
            return Map.of();
        }
        if (usernames.size() > MAX_BATCH_STATUS_SIZE) {
            throw new BadRequestException("too many usernames in one batch (max " + MAX_BATCH_STATUS_SIZE + ")");
        }
        Map<String, UUID> idsByUsername = auth.findUserIdsByUsernames(new HashSet<>(usernames));
        Set<UUID> followingSet = findFolloweeIdsAmong(viewerId, idsByUsername.values());
        return idsByUsername.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> followingSet.contains(e.getValue())));
    }

    public Listing<PublicProfile> findFollowers(String username, Instant cursorCreatedAt, UUID cursorId, UUID viewerId) {
        UUID targetId = auth.findUserIdByUsername(username).orElseThrow(() -> new NotFoundException("no such user"));
        List<Follow> page = follows.findFollowersPage(targetId, cursorCreatedAt, cursorId, Pageable.ofSize(PAGE_SIZE));
        return toListing(page, Follow::getFollowerId, viewerId);
    }

    public Listing<PublicProfile> findFollowing(String username, Instant cursorCreatedAt, UUID cursorId, UUID viewerId) {
        UUID targetId = auth.findUserIdByUsername(username).orElseThrow(() -> new NotFoundException("no such user"));
        List<Follow> page = follows.findFollowingPage(targetId, cursorCreatedAt, cursorId, Pageable.ofSize(PAGE_SIZE));
        return toListing(page, Follow::getFolloweeId, viewerId);
    }

    // idOf picks which side of each Follow row is "the other user" — getFollowerId for a followers list,
    // getFolloweeId for a following list — reused for both the id-resolution lookup and the next cursor.
    private Listing<PublicProfile> toListing(List<Follow> page, Function<Follow, UUID> idOf, UUID viewerId) {
        List<UUID> orderedIds = page.stream().map(idOf).toList();
        List<User> resolvedUsers = auth.findUsersByIds(orderedIds);
        Set<UUID> followingSet = findFolloweeIdsAmong(viewerId, orderedIds);
        List<Thing<PublicProfile>> children = resolvedUsers.stream()
                .map(u -> new Thing<>(USER_KIND,
                        PublicProfile.from(u, viewerId == null ? null : followingSet.contains(u.getId()))))
                .toList();
        String next = page.isEmpty() ? null : CursorCodec.encode(page.getLast().getCreatedAt(), idOf.apply(page.getLast()));
        return Listing.of(children, next);
    }
}
