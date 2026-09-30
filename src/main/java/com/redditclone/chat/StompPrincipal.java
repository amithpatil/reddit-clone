package com.redditclone.chat;

import java.security.Principal;
import java.util.UUID;

// getName() returns the raw UUID string, matching how @AuthenticationPrincipal UUID already works for
// every HTTP controller in this app — convertAndSendToUser and a @MessageMapping method's injected
// Principal both key on this same string.
public record StompPrincipal(UUID userId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
