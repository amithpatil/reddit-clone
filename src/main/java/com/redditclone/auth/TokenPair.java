package com.redditclone.auth;

/**
 * Internal carrier for a freshly issued access + refresh token pair. The controller splits these:
 * the access token goes in the JSON body, the raw refresh token becomes an HttpOnly cookie — it never
 * appears in a response body.
 */
record TokenPair(String accessToken, String rawRefreshToken) {
}
