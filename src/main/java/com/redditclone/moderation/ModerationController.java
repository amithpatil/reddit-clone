package com.redditclone.moderation;

import com.redditclone.community.AutomodRule;
import com.redditclone.community.CommunityModerator;
import com.redditclone.community.CommunityService;
import com.redditclone.community.Flair;
import com.redditclone.community.dto.SetFlairRequest;
import com.redditclone.moderation.dto.AddModeratorRequest;
import com.redditclone.moderation.dto.AutomodRuleRequest;
import com.redditclone.moderation.dto.BanRequest;
import com.redditclone.moderation.dto.FlairRequest;
import com.redditclone.moderation.dto.ModMailRequest;
import com.redditclone.moderation.dto.MuteRequest;
import com.redditclone.moderation.dto.RemoveRequest;
import com.redditclone.moderation.dto.ReportRequest;
import com.redditclone.post.PostService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@RestController
public class ModerationController {

    private final ModerationService moderation;
    private final ReportService reportService;
    private final CommunityService communityService;
    private final PostService postService;
    private final ObjectMapper json;

    public ModerationController(ModerationService moderation, ReportService reportService,
                                 CommunityService communityService, PostService postService, ObjectMapper json) {
        this.moderation = moderation;
        this.reportService = reportService;
        this.communityService = communityService;
        this.postService = postService;
        this.json = json;
    }

    @PostMapping("/api/report")
    public Report report(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ReportRequest req) {
        return reportService.fileReport(userId, req.targetType(), req.targetId(), req.reason());
    }

    @GetMapping("/r/{name}/mod/queue")
    public List<ModQueueEntry> queue(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return moderation.listModQueue(userId, communityId(name));
    }

    @GetMapping("/r/{name}/mod/actions")
    public List<ModerationAction> actions(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return moderation.listModerationActions(userId, communityId(name));
    }

    @PostMapping("/r/{name}/mod/reports/{reportId}/resolve")
    public void resolveReport(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID reportId) {
        moderation.resolveReport(userId, communityId(name), reportId, "resolved");
    }

    @PostMapping("/r/{name}/mod/reports/{reportId}/dismiss")
    public void dismissReport(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID reportId) {
        moderation.resolveReport(userId, communityId(name), reportId, "dismissed");
    }

    @PostMapping("/r/{name}/mod/remove/{targetType}/{targetId}")
    public void remove(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                        @PathVariable String targetType, @PathVariable UUID targetId,
                        @RequestBody(required = false) RemoveRequest req) {
        moderation.removeContent(userId, communityId(name), targetType, targetId, req == null ? null : req.reason());
    }

    @PostMapping("/r/{name}/mod/ban")
    public void ban(@AuthenticationPrincipal UUID userId, @PathVariable String name, @Valid @RequestBody BanRequest req) {
        communityService.issueBan(userId, communityId(name), req.userId(), req.reason(), req.expiresAt());
    }

    @DeleteMapping("/r/{name}/mod/ban/{targetUserId}")
    public void unban(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        communityService.liftBan(userId, communityId(name), targetUserId, null);
    }

    @PostMapping("/r/{name}/mod/mute")
    public void mute(@AuthenticationPrincipal UUID userId, @PathVariable String name, @Valid @RequestBody MuteRequest req) {
        moderation.muteUser(userId, communityId(name), req.userId(), req.reason(), req.expiresAt());
    }

    @DeleteMapping("/r/{name}/mod/mute/{targetUserId}")
    public void unmute(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        moderation.unmuteUser(userId, communityId(name), targetUserId, null);
    }

    @PostMapping("/r/{name}/mod/automod-rules")
    public AutomodRule addAutomodRule(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                       @Valid @RequestBody AutomodRuleRequest req) {
        String config = json.writeValueAsString(req.config());
        return communityService.addAutomodRule(userId, communityId(name), req.ruleType(), config, req.action());
    }

    @GetMapping("/r/{name}/mod/automod-rules")
    public List<AutomodRule> listAutomodRules(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        communityService.requireAnyModPermission(userId, communityId(name));
        return communityService.listAutomodRules(communityId(name));
    }

    @DeleteMapping("/r/{name}/mod/automod-rules/{ruleId}")
    public void removeAutomodRule(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID ruleId) {
        communityService.removeAutomodRule(userId, communityId(name), ruleId);
    }

    @PostMapping("/r/{name}/mod/moderators")
    public void addModerator(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                              @Valid @RequestBody AddModeratorRequest req) {
        communityService.addModerator(userId, communityId(name), req.userId(), req.permissions());
    }

    @DeleteMapping("/r/{name}/mod/moderators/{targetUserId}")
    public void removeModerator(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        communityService.removeModerator(userId, communityId(name), targetUserId);
    }

    @PostMapping("/r/{name}/mod/mail")
    public ModMailMessage sendModMail(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                       @Valid @RequestBody ModMailRequest req) {
        return moderation.sendModMail(userId, communityId(name), req.body());
    }

    @GetMapping("/r/{name}/mod/mail")
    public List<ModMailMessage> listModMail(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return moderation.listModMail(userId, communityId(name));
    }

    @PostMapping("/r/{name}/mod/flairs")
    public Flair addFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                           @Valid @RequestBody FlairRequest req) {
        return communityService.addFlair(userId, communityId(name), req.text(), req.color(), req.type());
    }

    @DeleteMapping("/r/{name}/mod/flairs/{flairId}")
    public void removeFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID flairId) {
        communityService.removeFlair(userId, communityId(name), flairId);
    }

    @PatchMapping("/r/{name}/mod/users/{targetUserId}/flair")
    public void setUserFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                              @PathVariable UUID targetUserId, @RequestBody SetFlairRequest req) {
        communityService.setUserFlair(userId, communityId(name), targetUserId, req.flairId());
    }

    @PatchMapping("/r/{name}/mod/posts/{postId}/flair")
    public void setPostFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                              @PathVariable UUID postId, @RequestBody SetFlairRequest req) {
        UUID communityId = communityId(name);
        communityService.requirePermission(userId, communityId, CommunityModerator.PERM_MANAGE_FLAIRS);
        postService.setFlair(postId, communityId, req.flairId());
    }

    private UUID communityId(String name) {
        return communityService.findByName(name).getId();
    }
}
