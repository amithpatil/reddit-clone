package com.redditclone.moderation;

import com.redditclone.comment.CommentService;
import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.community.CommunityModerator;
import com.redditclone.community.CommunityService;
import com.redditclone.post.Post;
import com.redditclone.post.PostService;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ModerationService {

    // Caps the mod-queue/action-log/modmail list endpoints, which previously returned every row ever
    // inserted with no limit at all.
    private static final int LIST_PAGE_SIZE = 100;

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
    private final ModerationAuditWriter auditWriter;

    public ModerationService(CommunityService communityService, PostService postService, CommentService commentService,
                              ReportRepository reports, ModQueueRepository modQueue, ModerationActionRepository actions,
                              ModMailMessageRepository modMailMessages, ModMailMuteRepository modMailMutes,
                              UuidV7Generator ids, JdbcTemplate jdbc, ModerationAuditWriter auditWriter) {
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
        this.auditWriter = auditWriter;
    }

    @Transactional
    public void removeContent(UUID actorId, UUID communityId, String targetType, UUID targetId, String reason) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        requireTargetInCommunity(targetType, targetId, communityId);
        switch (targetType) {
            case "post" -> postService.markRemoved(targetId);
            case "comment" -> commentService.markRemoved(targetId);
            default -> throw new BadRequestException("targetType must be post or comment");
        }
        auditWriter.logAction(communityId, actorId, "remove_" + targetType, targetType, targetId, reason);
        deleteFromModQueue(communityId, targetType, targetId);
    }

    // A moderator's permission is only checked against the community named in the URL — without this,
    // a targetId from an unrelated community would still pass that check and get silently acted on.
    private void requireTargetInCommunity(String targetType, UUID targetId, UUID communityId) {
        UUID targetCommunityId = switch (targetType) {
            case "post" -> postService.findById(targetId).getCommunityId();
            case "comment" -> {
                Post post = postService.findById(commentService.findById(targetId).getPostId());
                yield post.getCommunityId();
            }
            default -> throw new BadRequestException("targetType must be post or comment");
        };
        if (!targetCommunityId.equals(communityId)) {
            throw new NotFoundException(targetType + " not found in this community");
        }
    }

    @Transactional
    public void resolveReport(UUID actorId, UUID communityId, UUID reportId, String outcome) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        if (!"resolved".equals(outcome) && !"dismissed".equals(outcome)) {
            throw new BadRequestException("outcome must be resolved or dismissed");
        }
        Report report = reports.findByReportId(reportId).orElseThrow(() -> new NotFoundException("report not found"));
        if (!report.getCommunityId().equals(communityId)) {
            throw new NotFoundException("report not found");
        }
        report.setStatus(outcome);
        report.setResolverId(actorId);
        reports.save(report);
        auditWriter.logAction(communityId, actorId, outcome + "_report", report.getTargetType(), report.getTargetId(), null);
        deleteFromModQueue(communityId, report.getTargetType(), report.getTargetId());
    }

    public List<ModQueueEntry> listModQueue(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        return modQueue.findByCommunityIdOrderByFirstReportedAtAsc(communityId, Pageable.ofSize(LIST_PAGE_SIZE));
    }

    public List<ModerationAction> listModerationActions(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        return actions.findByCommunityIdOrderByCreatedAtDesc(communityId, Pageable.ofSize(LIST_PAGE_SIZE));
    }

    @Transactional
    public void muteUser(UUID actorId, UUID communityId, UUID targetUserId, String reason, Instant expiresAt) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MUTE_USERS);
        ModMailMute mute = new ModMailMute(communityId, targetUserId, actorId, reason, expiresAt);
        // Re-muting the same user re-issues this row via JPA merge — preserve the original created_at
        // instead of letting merge overwrite it with the new instance's Instant.now() default.
        modMailMutes.findById(new ModMailMuteId(communityId, targetUserId))
                .ifPresent(existing -> mute.setCreatedAt(existing.getCreatedAt()));
        modMailMutes.save(mute);
        auditWriter.logAction(communityId, actorId, "mute", "user", targetUserId, reason);
    }

    @Transactional
    public void unmuteUser(UUID actorId, UUID communityId, UUID targetUserId, String reason) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MUTE_USERS);
        modMailMutes.deleteById(new ModMailMuteId(communityId, targetUserId));
        auditWriter.logAction(communityId, actorId, "unmute", "user", targetUserId, reason);
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
        return modMailMessages.findByCommunityIdOrderByCreatedAtDesc(communityId, Pageable.ofSize(LIST_PAGE_SIZE));
    }

    private void deleteFromModQueue(UUID communityId, String targetType, UUID targetId) {
        jdbc.update("DELETE FROM mod_queue WHERE community_id = ? AND target_type = ? AND target_id = ?",
                communityId, targetType, targetId);
    }
}
