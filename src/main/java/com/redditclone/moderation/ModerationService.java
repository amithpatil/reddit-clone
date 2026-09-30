package com.redditclone.moderation;

import com.redditclone.comment.CommentService;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.community.CommunityModerator;
import com.redditclone.community.CommunityService;
import com.redditclone.post.PostService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ModerationService {

    private final CommunityService communityService;
    private final PostService postService;
    private final CommentService commentService;
    private final ReportRepository reports;
    private final ModQueueRepository modQueue;
    private final ModerationActionRepository actions;
    private final ModMailMessageRepository modMailMessages;
    private final ModMailMuteRepository modMailMutes;
    private final UuidV7Generator ids;
    private final JdbcTemplate jdbc;

    public ModerationService(CommunityService communityService, PostService postService, CommentService commentService,
                              ReportRepository reports, ModQueueRepository modQueue, ModerationActionRepository actions,
                              ModMailMessageRepository modMailMessages, ModMailMuteRepository modMailMutes,
                              UuidV7Generator ids, JdbcTemplate jdbc) {
        this.communityService = communityService;
        this.postService = postService;
        this.commentService = commentService;
        this.reports = reports;
        this.modQueue = modQueue;
        this.actions = actions;
        this.modMailMessages = modMailMessages;
        this.modMailMutes = modMailMutes;
        this.ids = ids;
        this.jdbc = jdbc;
    }

    @Transactional
    public void removeContent(UUID actorId, UUID communityId, String targetType, UUID targetId, String reason) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        switch (targetType) {
            case "post" -> postService.markRemoved(targetId);
            case "comment" -> commentService.markRemoved(targetId);
            default -> throw new BadRequestException("targetType must be post or comment");
        }
        logAction(communityId, actorId, "remove_" + targetType, targetType, targetId, reason);
        deleteFromModQueue(communityId, targetType, targetId);
    }

    @Transactional
    public void resolveReport(UUID actorId, UUID communityId, UUID reportId, String outcome) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        if (!"resolved".equals(outcome) && !"dismissed".equals(outcome)) {
            throw new BadRequestException("outcome must be resolved or dismissed");
        }
        Report report = reports.findById(reportId).orElseThrow(() -> new NotFoundException("report not found"));
        report.setStatus(outcome);
        report.setResolverId(actorId);
        reports.save(report);
        logAction(communityId, actorId, outcome + "_report", report.getTargetType(), report.getTargetId(), null);
        deleteFromModQueue(communityId, report.getTargetType(), report.getTargetId());
    }

    public List<ModQueueEntry> listModQueue(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        return modQueue.findByCommunityIdOrderByFirstReportedAtAsc(communityId);
    }

    public List<ModerationAction> listModerationActions(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        return actions.findByCommunityIdOrderByCreatedAtDesc(communityId);
    }

    @Transactional
    public void muteUser(UUID actorId, UUID communityId, UUID targetUserId, String reason, Instant expiresAt) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MUTE_USERS);
        modMailMutes.save(new ModMailMute(communityId, targetUserId, actorId, reason, expiresAt));
        logAction(communityId, actorId, "mute", "user", targetUserId, reason);
    }

    @Transactional
    public void unmuteUser(UUID actorId, UUID communityId, UUID targetUserId, String reason) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MUTE_USERS);
        modMailMutes.deleteById(new ModMailMuteId(communityId, targetUserId));
        logAction(communityId, actorId, "unmute", "user", targetUserId, reason);
    }

    @Transactional
    public ModMailMessage sendModMail(UUID senderId, UUID communityId, String body) {
        modMailMutes.findById(new ModMailMuteId(communityId, senderId)).ifPresent(mute -> {
            if (mute.getExpiresAt() == null || mute.getExpiresAt().isAfter(Instant.now())) {
                throw new ForbiddenException("muted from sending modmail in this community");
            }
        });
        ModMailMessage m = new ModMailMessage();
        m.setId(ids.nextId());
        m.setCommunityId(communityId);
        m.setSenderId(senderId);
        m.setBody(body);
        return modMailMessages.save(m);
    }

    public List<ModMailMessage> listModMail(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        return modMailMessages.findByCommunityIdOrderByCreatedAtDesc(communityId);
    }

    private void deleteFromModQueue(UUID communityId, String targetType, UUID targetId) {
        jdbc.update("DELETE FROM mod_queue WHERE community_id = ? AND target_type = ? AND target_id = ?",
                communityId, targetType, targetId);
    }

    private void logAction(UUID communityId, UUID actorId, String action, String targetType, UUID targetId, String reason) {
        jdbc.update("""
                INSERT INTO moderation_actions (id, community_id, actor_id, action, target_type, target_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, ids.nextId(), communityId, actorId, action, targetType, targetId, reason);
    }
}
