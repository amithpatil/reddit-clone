package com.redditclone.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "nsfw_blur", nullable = false)
    private boolean nsfwBlur = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "privacy_prefs", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> privacyPrefs = new HashMap<>();

    // Sparse, same contract as privacyPrefs: an absent key means "enabled" (default-on), only an explicit
    // false disables a notification type. Read by notify.NotificationOutboxWorker via AuthService, never
    // directly, to keep the cross-module read going through this module's own service.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_prefs", columnDefinition = "jsonb", nullable = false)
    private Map<String, Boolean> notificationPrefs = new HashMap<>();

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isNsfwBlur() {
        return nsfwBlur;
    }

    public void setNsfwBlur(boolean nsfwBlur) {
        this.nsfwBlur = nsfwBlur;
    }

    public Map<String, Object> getPrivacyPrefs() {
        return privacyPrefs;
    }

    public void setPrivacyPrefs(Map<String, Object> privacyPrefs) {
        this.privacyPrefs = privacyPrefs;
    }

    public Map<String, Boolean> getNotificationPrefs() {
        return notificationPrefs;
    }

    public void setNotificationPrefs(Map<String, Boolean> notificationPrefs) {
        this.notificationPrefs = notificationPrefs;
    }
}
