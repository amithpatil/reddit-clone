package com.redditclone.common;

import com.github.f4b6a3.uuid.UuidCreator;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidV7Generator {
    public UUID nextId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
