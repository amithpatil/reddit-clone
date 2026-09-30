package com.redditclone.auth;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.UnauthorizedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final UserSettingsRepository userSettings;
    private final RefreshTokenRepository refreshTokens;
    private final AccountActionRepository accountActions;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final UuidV7Generator ids;

    public AuthService(UserRepository users, UserSettingsRepository userSettings,
                        RefreshTokenRepository refreshTokens, AccountActionRepository accountActions,
                        PasswordEncoder encoder, JwtService jwt, UuidV7Generator ids) {
        this.users = users;
        this.userSettings = userSettings;
        this.refreshTokens = refreshTokens;
        this.accountActions = accountActions;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ids = ids;
    }

    @Transactional
    public TokenPair register(String username, String email, String rawPassword) {
        if (users.existsByEmail(email)) {
            throw new ConflictException("email in use");
        }
        if (users.existsByUsername(username)) {
            throw new ConflictException("username in use");
        }
        User user = new User();
        user.setId(ids.nextId());
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(encoder.encode(rawPassword));
        users.save(user);

        UserSettings settings = new UserSettings();
        settings.setUserId(user.getId());
        userSettings.save(settings);

        return issueTokens(user, ids.nextId());
    }

    public TokenPair login(String username, String rawPassword) {
        User user = users.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("invalid credentials"));
        if (!encoder.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("invalid credentials");
        }
        // Checked after credential verification, not before — don't leak account status to a guesser
        // who doesn't even have the right password.
        if (!"active".equals(user.getStatus())) {
            throw new UnauthorizedException("account is not active");
        }
        return issueTokens(user, ids.nextId());
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        if (stored.getRevokedAt() != null) {
            refreshTokens.revokeFamily(stored.getFamilyId()); // reuse of a dead token = theft signal
            throw new UnauthorizedException("token reuse detected");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("refresh token expired");
        }
        stored.setRevokedAt(Instant.now());
        refreshTokens.save(stored);
        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        // Same status check as login(), right where the User row is already loaded for the family-id
        // lookup (no extra query) — closes the loop so a ban also immediately kills the ability to mint
        // a *new* access token, not just future logins. The already-issued access token this call would
        // have refreshed keeps running until its own short TTL expires regardless (see banAccount).
        if (!"active".equals(user.getStatus())) {
            throw new UnauthorizedException("account is not active");
        }
        return issueTokens(user, stored.getFamilyId());
    }

    // Read by post.PostService.create() / comment.CommentService.reply() to evaluate a community's
    // karma_threshold automod rules.
    public int getKarmaPost(UUID userId) {
        return users.findById(userId).map(User::getKarmaPost).orElse(0);
    }

    public int getKarmaComment(UUID userId) {
        return users.findById(userId).map(User::getKarmaComment).orElse(0);
    }

    public void requireSiteAdmin(UUID userId) {
        if (!users.findById(userId).map(User::isSiteAdmin).orElse(false)) {
            throw new ForbiddenException("site admin only");
        }
    }

    // Account-level (site-wide) ban — independent of any community.CommunityService.issueBan, which is
    // scoped to one community. Reuses revokeAllForUser (already built for account deletion, unused until
    // now): the refresh-token family dies immediately; the still-valid access token this doesn't touch
    // keeps running for its own short remaining TTL — see refresh()'s comment for why that's accepted.
    @Transactional
    public void banAccount(UUID actorId, UUID targetUserId, String reason) {
        requireSiteAdmin(actorId);
        User target = users.findById(targetUserId).orElseThrow(() -> new UnauthorizedException("no such user"));
        target.setStatus("banned");
        users.save(target);
        refreshTokens.revokeAllForUser(targetUserId);
        logAccountAction(actorId, targetUserId, "ban_account", reason);
    }

    @Transactional
    public void unbanAccount(UUID actorId, UUID targetUserId, String reason) {
        requireSiteAdmin(actorId);
        User target = users.findById(targetUserId).orElseThrow(() -> new UnauthorizedException("no such user"));
        target.setStatus("active");
        users.save(target);
        logAccountAction(actorId, targetUserId, "unban_account", reason);
    }

    // Idempotent: used by SystemAccountBootstrap on every app startup, not just the first time.
    @Transactional
    public void promoteToSiteAdmin(String username) {
        users.findByUsername(username).filter(u -> !u.isSiteAdmin()).ifPresent(u -> {
            u.setSiteAdmin(true);
            users.save(u);
        });
    }

    private void logAccountAction(UUID actorId, UUID targetUserId, String action, String reason) {
        AccountAction a = new AccountAction();
        a.setId(ids.nextId());
        a.setActorId(actorId);
        a.setTargetUserId(targetUserId);
        a.setAction(action);
        a.setReason(reason);
        accountActions.save(a);
    }

    // Grouped karma deltas from a batch of vote events (see vote.OutboxWorker) — one bulk UPDATE per
    // affected user, not per post/comment, same "grouped, not per-vote" principle as PostService/
    // CommentService.applyVoteDeltas.
    @Transactional
    public void applyPostKarmaDelta(UUID userId, int delta) {
        if (delta != 0) {
            users.adjustKarmaPost(userId, delta);
        }
    }

    @Transactional
    public void applyCommentKarmaDelta(UUID userId, int delta) {
        if (delta != 0) {
            users.adjustKarmaComment(userId, delta);
        }
    }

    private TokenPair issueTokens(User user, UUID familyId) {
        String access = jwt.generateAccessToken(user);
        String rawRefresh = UUID.randomUUID().toString(); // the opaque refresh secret itself — a plain random UUID is fine here, it's never used as a sortable primary key
        RefreshToken rt = new RefreshToken();
        rt.setId(ids.nextId());
        rt.setUserId(user.getId());
        rt.setTokenHash(sha256(rawRefresh));
        rt.setFamilyId(familyId);
        rt.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        refreshTokens.save(rt);
        return new TokenPair(access, rawRefresh);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
