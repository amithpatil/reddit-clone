package com.redditclone.engagement;

import com.redditclone.common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SavedItemService {

    private final SavedItemRepository savedItems;

    public SavedItemService(SavedItemRepository savedItems) {
        this.savedItems = savedItems;
    }

    // No existence check against the target post/comment id — unlike vote/media, a bogus saved target id
    // has no downstream side effect to corrupt (it just never matches anything), so the stricter
    // "never trust a client id without resolving it" rule applied elsewhere in this codebase doesn't
    // carry the same cost here.
    @Transactional
    public void save(UUID userId, String targetType, UUID targetId) {
        requireValidTargetType(targetType);
        if (savedItems.existsByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId)) {
            return; // idempotent
        }
        savedItems.save(new SavedItem(userId, targetType, targetId));
    }

    @Transactional
    public void unsave(UUID userId, String targetType, UUID targetId) {
        requireValidTargetType(targetType);
        savedItems.deleteByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
    }

    private void requireValidTargetType(String targetType) {
        if (!"post".equals(targetType) && !"comment".equals(targetType)) {
            throw new BadRequestException("targetType must be post or comment");
        }
    }
}
