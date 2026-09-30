package com.redditclone.auth.dto;

import java.util.Map;

public record UpdatePrefsRequest(Boolean nsfwBlur, Map<String, Object> privacyPrefs) {
}
