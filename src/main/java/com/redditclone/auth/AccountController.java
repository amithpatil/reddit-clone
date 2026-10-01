package com.redditclone.auth;

import com.redditclone.auth.dto.DeleteAccountRequest;
import com.redditclone.auth.dto.UpdatePrefsRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

    private final AuthService authService;

    public AccountController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/prefs")
    public UserSettings getPrefs(@AuthenticationPrincipal UUID userId) {
        return authService.getSettings(userId);
    }

    @PatchMapping("/prefs")
    public UserSettings updatePrefs(@AuthenticationPrincipal UUID userId, @RequestBody UpdatePrefsRequest req) {
        return authService.updateSettings(userId, req.nsfwBlur(), req.privacyPrefs(), req.notificationPrefs());
    }

    @DeleteMapping
    public void deleteAccount(@AuthenticationPrincipal UUID userId, @Valid @RequestBody DeleteAccountRequest req) {
        authService.deleteAccount(userId, req.password());
    }
}
