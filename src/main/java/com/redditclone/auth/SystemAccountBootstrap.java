package com.redditclone.auth;

import com.redditclone.common.SystemAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Runs on every startup, idempotent both times: (1) ensures the AutoModerator bot account exists, so
// community.CommunityService.evaluateAutomod always has a real, valid actor_id to attribute automod
// actions to; (2) if app.admin.bootstrap-username is configured, promotes that user to site admin. A
// no-op on every boot after the first successful run — there's no self-service "promote another admin"
// endpoint, since bootstrapping the first (and for a project this size, probably only) admin is the
// actual problem this solves.
@Component
public class SystemAccountBootstrap implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthService authService;
    private final String adminBootstrapUsername;

    public SystemAccountBootstrap(UserRepository users, PasswordEncoder encoder, AuthService authService,
                                   @Value("${app.admin.bootstrap-username:}") String adminBootstrapUsername) {
        this.users = users;
        this.encoder = encoder;
        this.authService = authService;
        this.adminBootstrapUsername = adminBootstrapUsername;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.findById(SystemAccounts.AUTOMOD_USER_ID).isEmpty()) {
            User bot = new User();
            bot.setId(SystemAccounts.AUTOMOD_USER_ID);
            bot.setUsername("AutoModerator");
            bot.setEmail("automod@system.internal");
            bot.setPasswordHash(encoder.encode(UUID.randomUUID().toString())); // unusable — no login flow ever needs this
            users.save(bot);
        }
        if (!adminBootstrapUsername.isBlank()) {
            authService.promoteToSiteAdmin(adminBootstrapUsername);
        }
    }
}
