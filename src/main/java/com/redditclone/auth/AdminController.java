package com.redditclone.auth;

import com.redditclone.auth.dto.ModerationReasonRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Site-wide (account-level) moderation — distinct from community.ModerationController, which is scoped
// to one community's moderators. Every endpoint here requires AuthService.requireSiteAdmin.
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminController {

    private final AuthService auth;

    public AdminController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/{userId}/ban")
    public void ban(@AuthenticationPrincipal UUID actorId, @PathVariable UUID userId,
                     @RequestBody ModerationReasonRequest req) {
        auth.banAccount(actorId, userId, req.reason());
    }

    @PostMapping("/{userId}/unban")
    public void unban(@AuthenticationPrincipal UUID actorId, @PathVariable UUID userId,
                       @RequestBody ModerationReasonRequest req) {
        auth.unbanAccount(actorId, userId, req.reason());
    }
}
