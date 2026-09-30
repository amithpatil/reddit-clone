package com.redditclone.community;

import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.SystemAccounts;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class CommunityService {

    // Isolates user-supplied automod regex matching (which can carry a catastrophic-backtracking
    // pattern) onto a small dedicated pool with a hard timeout, so it can never exhaust the shared
    // request-handling thread pool the way an unbounded synchronous match could.
    private static final long REGEX_MATCH_TIMEOUT_MS = 100;

    private final CommunityRepository communities;
    private final MembershipRepository memberships;
    private final CommunityModeratorRepository moderators;
    private final BanRepository bans;
    private final AutomodRuleRepository automodRules;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final ModerationAuditWriter auditWriter;
    private final ExecutorService regexExecutor =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "automod-regex");
                t.setDaemon(true);
                return t;
            });

    public CommunityService(CommunityRepository communities, MembershipRepository memberships,
                             CommunityModeratorRepository moderators, BanRepository bans,
                             AutomodRuleRepository automodRules, UuidV7Generator ids,
                             ObjectMapper json, ModerationAuditWriter auditWriter) {
        this.communities = communities;
        this.memberships = memberships;
        this.moderators = moderators;
        this.bans = bans;
        this.automodRules = automodRules;
        this.ids = ids;
        this.json = json;
        this.auditWriter = auditWriter;
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
        Ban ban = new Ban(communityId, targetUserId, issuerId, reason, expiresAt);
        // Re-banning the same user re-issues this row via JPA merge (app-assigned id, never persist()) —
        // preserve the original created_at instead of letting merge overwrite it with Instant.now().
        bans.findById(new BanId(communityId, targetUserId)).ifPresent(existing -> ban.setCreatedAt(existing.getCreatedAt()));
        bans.save(ban);
        auditWriter.logAction(communityId, issuerId, "ban", "user", targetUserId, reason);
    }

    @Transactional
    public void liftBan(UUID issuerId, UUID communityId, UUID targetUserId, String reason) {
        requirePermission(issuerId, communityId, CommunityModerator.PERM_BAN_USERS);
        bans.deleteById(new BanId(communityId, targetUserId));
        auditWriter.logAction(communityId, issuerId, "unban", "user", targetUserId, reason);
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
        // Cap the grant to a subset of the actor's own permissions — otherwise a moderator who only holds
        // PERM_MANAGE_MODERATORS could grant themselves (or anyone) OWNER_PERMISSIONS.
        int grantorPermissions = moderators.findByCommunityIdAndUserId(communityId, actorId)
                .map(CommunityModerator::getPermissions).orElse(0);
        if ((permissions & ~grantorPermissions) != 0) {
            throw new ForbiddenException("cannot grant permissions beyond your own");
        }
        CommunityModerator mod = new CommunityModerator(communityId, targetUserId, permissions, actorId);
        // Re-adding an existing moderator re-issues this row via JPA merge — preserve the original
        // added_at instead of letting merge overwrite it with the new instance's Instant.now() default.
        moderators.findByCommunityIdAndUserId(communityId, targetUserId)
                .ifPresent(existing -> mod.setAddedAt(existing.getAddedAt()));
        moderators.save(mod);
    }

    @Transactional
    public void removeModerator(UUID actorId, UUID communityId, UUID targetUserId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_MODERATORS);
        Community community = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        if (targetUserId.equals(community.getCreatorId())) {
            throw new ForbiddenException("cannot remove the community's owner");
        }
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
        AutomodRule rule = automodRules.findById(ruleId).orElseThrow(() -> new NotFoundException("no such automod rule"));
        if (!rule.getCommunityId().equals(communityId)) {
            throw new NotFoundException("no such automod rule");
        }
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
            auditWriter.logAction(communityId, SystemAccounts.AUTOMOD_USER_ID,
                    removes ? "automod_remove" : "automod_report", targetType, targetId,
                    "matched automod rule " + rule.getId() + " (" + rule.getRuleType() + ")");
            if (!removes) {
                auditWriter.upsertModQueue(communityId, targetType, targetId);
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
                    String pattern = config.path("pattern").asString();
                    if (pattern == null || pattern.isBlank()) {
                        yield false; // missing/mistyped 'pattern' key: skip rather than matching everything
                    }
                    try {
                        yield matchesWithTimeout(Pattern.compile(pattern), haystack);
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

    // A moderator-supplied regex can carry a catastrophic-backtracking pattern; running it inline on the
    // request thread would let it hang that thread indefinitely. Bounding it to a small dedicated pool
    // with a hard timeout means a bad pattern can only ever burn those background threads, never the
    // shared servlet/DB-transaction pool every other request depends on. A timeout is treated as "no
    // match" — the same fail-safe already used for a malformed pattern above.
    private boolean matchesWithTimeout(Pattern pattern, String haystack) {
        Future<Boolean> future = regexExecutor.submit(() -> pattern.matcher(haystack).find());
        try {
            return future.get(REGEX_MATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return false;
        }
    }
}
