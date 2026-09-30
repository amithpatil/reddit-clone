package com.redditclone.engagement;

import com.redditclone.common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class HiddenItemService {

    private final HiddenItemRepository hiddenItems;

    public HiddenItemService(HiddenItemRepository hiddenItems) {
        this.hiddenItems = hiddenItems;
    }

    @Transactional
    public void hide(UUID userId, String targetType, UUID targetId) {
        requireValidTargetType(targetType);
        if (hiddenItems.existsByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId)) {
            return; // idempotent
        }
        hiddenItems.save(new HiddenItem(userId, targetType, targetId));
    }

    @Transactional
    public void unhide(UUID userId, String targetType, UUID targetId) {
        requireValidTargetType(targetType);
        hiddenItems.deleteByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
    }

    private void requireValidTargetType(String targetType) {
        // hidden_items has no DB-level CHECK on target_type (unlike saved_items) — enforced here instead
        // so both features validate the same way regardless of that schema asymmetry.
        if (!"post".equals(targetType) && !"comment".equals(targetType)) {
            throw new BadRequestException("targetType must be post or comment");
        }
    }
}
