package com.redditclone.engagement;

import com.redditclone.engagement.dto.TargetRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class EngagementController {

    private final SavedItemService savedItems;
    private final HiddenItemService hiddenItems;

    public EngagementController(SavedItemService savedItems, HiddenItemService hiddenItems) {
        this.savedItems = savedItems;
        this.hiddenItems = hiddenItems;
    }

    @PostMapping("/api/save")
    public void save(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TargetRequest req) {
        savedItems.save(userId, req.targetType(), req.targetId());
    }

    @DeleteMapping("/api/save")
    public void unsave(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TargetRequest req) {
        savedItems.unsave(userId, req.targetType(), req.targetId());
    }

    @PostMapping("/api/hide")
    public void hide(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TargetRequest req) {
        hiddenItems.hide(userId, req.targetType(), req.targetId());
    }

    @DeleteMapping("/api/hide")
    public void unhide(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TargetRequest req) {
        hiddenItems.unhide(userId, req.targetType(), req.targetId());
    }
}
