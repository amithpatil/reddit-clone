package com.redditclone.follow;

import com.redditclone.auth.dto.PublicProfile;
import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class FollowController {

    private final FollowService follows;

    public FollowController(FollowService follows) {
        this.follows = follows;
    }

    // Authenticated by default (no SecurityConfig entry needed) — same as POST/DELETE /r/{name}/subscribe.
    // "changed" lets the frontend reconcile its optimistic follower count against the real outcome rather
    // than always trusting its own pre-click guess (see FollowService.follow's doc comment).
    @PostMapping("/user/{username}/follow")
    public Map<String, Boolean> follow(@AuthenticationPrincipal UUID userId, @PathVariable String username) {
        return Map.of("changed", follows.follow(userId, username));
    }

    @DeleteMapping("/user/{username}/follow")
    public Map<String, Boolean> unfollow(@AuthenticationPrincipal UUID userId, @PathVariable String username) {
        return Map.of("changed", follows.unfollow(userId, username));
    }

    // Authenticated-only (no SecurityConfig entry): the frontend only ever calls this for a logged-in
    // viewer, since an anonymous one has nothing to check — auth.UserController.about() can't attach this
    // itself (auth has no dependency on follow).
    @GetMapping("/user/{username}/follow")
    public Map<String, Boolean> followStatus(@AuthenticationPrincipal UUID viewerId, @PathVariable String username) {
        return Map.of("isFollowing", follows.isFollowing(viewerId, username));
    }

    // Batch counterpart of followStatus for a page of results (e.g. Search's People tab) —
    // auth.UserController.search() can't attach isFollowing itself for the same reason. Authenticated-only,
    // same reasoning as followStatus.
    @PostMapping("/user/follow-status")
    public Map<String, Boolean> followStatusBatch(@AuthenticationPrincipal UUID viewerId, @RequestBody List<String> usernames) {
        return follows.findFollowingStatusByUsernames(viewerId, usernames);
    }

    // Public (listed in SecurityConfig's GET permitAll block) — same "existence/metadata is public" category
    // as /user/*/submitted and /user/*/comments.
    @GetMapping("/user/{username}/followers")
    public Listing<PublicProfile> followers(@AuthenticationPrincipal UUID viewerId, @PathVariable String username,
                                             @RequestParam(required = false) String after) {
        Cursor cursor = CursorCodec.decode(after);
        return follows.findFollowers(username, cursor.createdAt(), cursor.id(), viewerId);
    }

    @GetMapping("/user/{username}/following")
    public Listing<PublicProfile> following(@AuthenticationPrincipal UUID viewerId, @PathVariable String username,
                                             @RequestParam(required = false) String after) {
        Cursor cursor = CursorCodec.decode(after);
        return follows.findFollowing(username, cursor.createdAt(), cursor.id(), viewerId);
    }
}
