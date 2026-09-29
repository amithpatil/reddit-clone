package com.redditclone.common.text;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Component;

@Component
public class Sanitizer {

    private final PolicyFactory policy = Sanitizers.FORMATTING.and(Sanitizers.LINKS);

    public String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        return policy.sanitize(raw);
    }
}
