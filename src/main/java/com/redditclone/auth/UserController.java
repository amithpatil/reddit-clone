package com.redditclone.auth;

import com.redditclone.auth.dto.PublicProfile;
import com.redditclone.auth.dto.UserView;
import com.redditclone.common.exception.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/user/{username}/about")
    public PublicProfile about(@PathVariable String username) {
        return PublicProfile.from(users.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("no such user")));
    }
}
