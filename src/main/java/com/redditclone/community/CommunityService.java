package com.redditclone.community;

import com.redditclone.common.SystemAccounts;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class CommunityService {

    private final CommunityRepository communities;
    private final MembershipRepository memberships;
    private final CommunityModeratorRepository moderators;
    private final BanRepository bans;
    private final AutomodRuleRepository automodRules;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public CommunityService(CommunityRepository communities, MembershipRepository memberships,
                             CommunityModeratorRepository moderators, BanRepository bans,
                             AutomodRuleRepository automodRules, UuidV7Generator ids,
                             ObjectMapper json, JdbcTemplate jdbc) {
        this.communities = communities;
        this.memberships = memberships;
        this.moderators = moderators;
        this.bans = bans;
        this.automodRules = automodRules;
        this.ids = ids;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Transactional
    public Community create(UUID creatorId, String name, String description) {
        if (communities.existsByName(name)) {
            throw new ConflictException("community name in use");
        }
        Community c = new Community();
        c.setId(ids.nextId());
        c.setName(name);
        c.setDescription(description);
        c.setCreatorId(creatorId);
        c.setSubscriberCount(1); // creator auto-joins as the first member
        communities.save(c);
        // Insert the creator's membership directly rather than calling join(): join()'s existsBy check
        // can only ever be false here (c.getId() was just generated), and calling it would also be a
        // Spring self-invocation that silently bypasses join()'s own @Transactional proxy.
        memberships.save(new Membership(creatorId, c.getId(), Instant.now()));
        moderators.save(new CommunityModerator(c.getId(), creatorId, CommunityModerator.OWNER_PERMISSIONS, creatorId));
        return c;
    }

    public Community findByName(String name) {
        return communities.findByName(name).orElseThrow(() -> new NotFoundException("no such community"));
    }

    @Transactional
    public void join(UUID userId, UUID communityId) {
        if (memberships.existsByUserIdAndCommunityId(userId, communityId)) {
            return; // idempotent
        }
        memberships.save(new Membership(userId, communityId, Instant.now()));
        communities.incrementSubscriberCount(communityId);
    }

    @Transactional
    public void leave(UUID userId, UUID communityId) {
        if (memberships.deleteByUserIdAndCommunityId(userId, communityId) > 0) {
            communities.decrementSubscriberCount(communityId);
        }
    }

    // ==================== Moderation: bans ====================

    // A ban blocks posting/commenting only (not voting or browsing) — matches Reddit's own community-ban
    // behavior. One mechanism differentiated by expiry: null = permanent, set = temporary/timeout, with
    // no explicit unban action needed for the temporary case (it just stops matching once expired).
    public void requireNotBanned(UUID userId, UUID communityId) {
        Optional<Ban> ban = bans.findById(new BanId(communityId, userId));
        if (ban.isPresent() && isActive(ban.get())) {
            throw new ForbiddenException("banned from this community");
        }
    }

    private boolean isActive(Ban ban) {
        return ban.getExpiresAt() == null || ban.getExpiresAt().isAfter(Instant.now());
    }

    @Transactional
    public void issueBan(UUID issuerId, UUID communityId, UUID targetUserId, String reason, Instant expiresAt) {
        requirePermission(issuerId, communityId, CommunityModerator.PERM_BAN_USERS);
        bans.save(new Ban(communityId, targetUserId, issuerId, reason, expiresAt));
        logModerationAction(communityId, issuerId, "ban", "user", targetUserId, reason);
    }

    @Transactional
    public void liftBan(UUID issuerId, UUID communityId, UUID targetUserId, String reason) {
        requirePermission(issuerId, communityId, CommunityModerator.PERM_BAN_USERS);
        bans.deleteById(new BanId(communityId, targetUserId));
        logModerationAction(communityId, issuerId, "unban", "user", targetUserId, reason);
    }

    // ==================== Moderation: permissions ====================

    public boolean hasModPermission(UUID userId, UUID communityId, int requiredBit) {
        return moderators.findByCommunityIdAndUserId(communityId, userId)
                .map(m -> (m.getPermissions() & requiredBit) == requiredBit)
                .orElse(false);
    }

    public void requirePermission(UUID userId, UUID communityId, int requiredBit) {
        if (!hasModPermission(userId, communityId, requiredBit)) {
            throw new ForbiddenException("missing required moderator permission");
        }
    }

    public void requireAnyModPermission(UUID userId, UUID communityId) {
        if (!moderators.existsByCommunityIdAndUserId(communityId, userId)) {
            throw new ForbiddenException("not a moderator of this community");
        }
    }

    @Transactional
    public void addModerator(UUID actorId, UUID communityId, UUID targetUserId, int permissions) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_MODERATORS);
        moderators.save(new CommunityModerator(communityId, targetUserId, permissions, actorId));
    }

    @Transactional
    public void removeModerator(UUID actorId, UUID communityId, UUID targetUserId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_MODERATORS);
        moderators.deleteByCommunityIdAndUserId(communityId, targetUserId);
    }

    // ==================== Automod ====================

    public List<AutomodRule> listAutomodRules(UUID communityId) {
        return automodRules.findByCommunityId(communityId);
    }

    @Transactional
    public AutomodRule addAutomodRule(UUID actorId, UUID communityId, String ruleType, String config, String action) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_AUTOMOD);
        AutomodRule rule = new AutomodRule();
        rule.setId(ids.nextId());
        rule.setCommunityId(communityId);
        rule.setRuleType(ruleType);
        rule.setConfig(config);
        rule.setAction(action);
        return automodRules.save(rule);
    }

    @Transactional
    public void removeAutomodRule(UUID actorId, UUID communityId, UUID ruleId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_AUTOMOD);
        automodRules.deleteById(ruleId);
    }

    // Evaluated synchronously at submission time (PostService.create / CommentService.reply), before the
    // content is saved — cheap keyword/regex/karma checks, not worth an async pass. Returns true if the
    // content should be saved as already-removed. targetId must already be assigned (this app generates
    // ids app-side via UuidV7Generator before save, so it's available here) so the audit/report rows this
    // writes can reference the real target from the moment it's created.
    @Transactional
    public boolean evaluateAutomod(UUID communityId, String targetType, UUID targetId,
                                    String title, String body, int authorKarma) {
        String haystack = ((title == null ? "" : title) + " " + (body == null ? "" : body)).toLowerCase();
        boolean shouldRemove = false;
        for (AutomodRule rule : automodRules.findByCommunityIdAndEnabledTrue(communityId)) {
            if (!matches(rule, haystack, authorKarma)) {
                continue;
            }
            boolean removes = "remove".equals(rule.getAction());
            shouldRemove |= removes;
            logModerationAction(communityId, SystemAccounts.AUTOMOD_USER_ID,
                    removes ? "automod_remove" : "automod_report", targetType, targetId,
                    "matched automod rule " + rule.getId() + " (" + rule.getRuleType() + ")");
            if (!removes) {
                upsertModQueue(communityId, targetType, targetId);
            }
        }
        return shouldRemove;
    }

    private boolean matches(AutomodRule rule, String haystack, int authorKarma) {
        try {
            JsonNode config = json.readTree(rule.getConfig());
            return switch (rule.getRuleType()) {
                case "keyword" -> {
                    for (JsonNode kw : config.path("keywords")) {
                        if (haystack.contains(kw.asString().toLowerCase())) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case "regex" -> {
                    try {
                        yield Pattern.compile(config.path("pattern").asString()).matcher(haystack).find();
                    } catch (PatternSyntaxException e) {
                        yield false; // a malformed rule shouldn't block every submission in the community
                    }
                }
                case "karma_threshold" -> authorKarma < config.path("minKarma").asInt();
                default -> false;
            };
        } catch (Exception e) {
            return false; // corrupt rule config: skip it, don't fail the submission it's guarding
        }
    }

    // ==================== Shared infrastructure tables (raw SQL, not a cross-module repository — same
    // treatment OutboxWorker already gives outbox_events, and for the same reason: moderation_actions/
    // mod_queue are genuinely shared infrastructure fed from multiple modules, not owned by any one of
    // them; importing a `moderation`-module repository class here would recreate the exact
    // post/comment<->moderation cycle this design avoids). ====================

    private void logModerationAction(UUID communityId, UUID actorId, String action, String targetType, UUID targetId, String reason) {
        jdbc.update("""
                INSERT INTO moderation_actions (id, community_id, actor_id, action, target_type, target_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, ids.nextId(), communityId, actorId, action, targetType, targetId, reason);
    }

    private void upsertModQueue(UUID communityId, String targetType, UUID targetId) {
        jdbc.update("""
                INSERT INTO mod_queue (community_id, target_type, target_id, report_count, first_reported_at)
                VALUES (?, ?, ?, 1, now())
                ON CONFLICT (community_id, target_type, target_id) DO UPDATE SET report_count = mod_queue.report_count + 1
                """, communityId, targetType, targetId);
    }
}
