package com.redditclone.auth;

import com.redditclone.auth.dto.PublicProfile;
import com.redditclone.auth.dto.UserView;
import com.redditclone.common.exception.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/v1/me")
    public UserView me(@AuthenticationPrincipal UUID userId) {
        return UserView.from(users.findById(userId).orElseThrow(() -> new NotFoundException("user not found")));
    }

    // isFollowing is always null here — auth deliberately has no dependency on follow (every other module
    // depends on auth, never the reverse; see ModuleBoundaryTest's cycle-freedom rule). A logged-in viewer's
    // follow status for this profile is resolved by a separate call to follow.FollowController's
    // GET /user/{username}/follow, authenticated-only so an anonymous caller never needs it.
    @GetMapping("/user/{username}/about")
    public PublicProfile about(@PathVariable String username) {
        return PublicProfile.from(users.findByUsername(username).orElseThrow(() -> new NotFoundException("no such user")));
    }

    // Same isFollowing-is-always-null reasoning as about() above — a logged-in viewer resolves follow
    // status for a page of results via follow.FollowController's POST /user/follow-status.
    @GetMapping("/user/search")
    public List<PublicProfile> search(@RequestParam("q") String query) {
        return users.searchByUsername(query).stream().map(PublicProfile::from).toList();
    }
}
