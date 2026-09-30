package com.redditclone.moderation;

import com.redditclone.comment.CommentService;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.post.PostService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Callable by any authenticated user (not moderator-gated) — filing a report is a user action, distinct
// from ModerationService's moderator-only actions.
@Service
public class ReportService {

    private final ReportRepository reports;
    private final PostService postService;
    private final CommentService commentService;
    private final UuidV7Generator ids;
    private final JdbcTemplate jdbc;

    public ReportService(ReportRepository reports, PostService postService, CommentService commentService,
                          UuidV7Generator ids, JdbcTemplate jdbc) {
        this.reports = reports;
        this.postService = postService;
        this.commentService = commentService;
        this.ids = ids;
        this.jdbc = jdbc;
    }

    // communityId is resolved from the target here, never trusted from the client — a report's
    // community_id has to be the target's real community regardless of what a caller claims.
    @Transactional
    public Report fileReport(UUID reporterId, String targetType, UUID targetId, String reason) {
        UUID communityId = switch (targetType) {
            case "post" -> postService.findById(targetId).getCommunityId();
            case "comment" -> postService.findById(commentService.findById(targetId).getPostId()).getCommunityId();
            default -> throw new BadRequestException("targetType must be post or comment");
        };
        Report r = new Report();
        r.setId(ids.nextId());
        r.setTargetType(targetType);
        r.setTargetId(targetId);
        r.setCommunityId(communityId);
        r.setReporterId(reporterId);
        r.setReason(reason);
        Report saved = reports.save(r);
        jdbc.update("""
                INSERT INTO mod_queue (community_id, target_type, target_id, report_count, first_reported_at)
                VALUES (?, ?, ?, 1, now())
                ON CONFLICT (community_id, target_type, target_id) DO UPDATE SET report_count = mod_queue.report_count + 1
                """, communityId, targetType, targetId);
        return saved;
    }
}
