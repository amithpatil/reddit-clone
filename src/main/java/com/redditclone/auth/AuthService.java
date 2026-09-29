package com.redditclone.auth;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.ConflictException;
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
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final UuidV7Generator ids;

    public AuthService(UserRepository users, UserSettingsRepository userSettings,
                        RefreshTokenRepository refreshTokens, PasswordEncoder encoder,
                        JwtService jwt, UuidV7Generator ids) {
        this.users = users;
        this.userSettings = userSettings;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ids = ids;
    }

    @Transactional
    public TokenPair register(String username, String email, String rawPassword) {
        if (users.existsByEmail(email)) {
            throw new ConflictException("email in use");
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
        stored.setRevokedAt(Instant.now());
        refreshTokens.save(stored);
        User user = users.findById(stored.getUserId()).orElseThrow();
        return issueTokens(user, stored.getFamilyId());
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
