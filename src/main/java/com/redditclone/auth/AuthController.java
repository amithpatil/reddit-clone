package com.redditclone.auth;

import com.redditclone.auth.dto.AuthResponse;
import com.redditclone.auth.dto.LoginRequest;
import com.redditclone.auth.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        TokenPair tokens = auth.register(req.username(), req.email(), req.password());
        return withRefreshCookie(tokens);
    }

    @PostMapping("/access_token")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        TokenPair tokens = auth.login(req.username(), req.password());
        return withRefreshCookie(tokens);
    }

    @PostMapping("/access_token/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(REFRESH_COOKIE) String refreshToken) {
        TokenPair tokens = auth.refresh(refreshToken);
        return withRefreshCookie(tokens);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null) {
            auth.logout(refreshToken);
        }
        ResponseCookie expired = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(false) // flip to true once Caddy/TLS terminates the connection (Phase 5)
                .sameSite("Strict")
                .path("/api/v1")
                .maxAge(0)
                .build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(TokenPair tokens) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, tokens.rawRefreshToken())
                .httpOnly(true)
                .secure(false) // flip to true once Caddy/TLS terminates the connection (Phase 5)
                .sameSite("Strict")
                .path("/api/v1")
                .maxAge(Duration.ofDays(30))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(AuthResponse.bearer(tokens.accessToken()));
    }
}
