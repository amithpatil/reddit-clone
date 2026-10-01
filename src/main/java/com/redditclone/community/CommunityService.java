package com.redditclone.community;

import com.redditclone.auth.AuthService;
import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.SystemAccounts;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.community.dto.CommunityRule;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final FlairRepository flairs;
    private final CommunityJoinRequestRepository joinRequests;
    private final CommunityApprovedSubmitterRepository approvedSubmitters;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final ModerationAuditWriter auditWriter;
    private final AuthService authService;
    private final ExecutorService regexExecutor =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "automod-regex");
                t.setDaemon(true);
                return t;
            });

    public CommunityService(CommunityRepository communities, MembershipRepository memberships,
                             CommunityModeratorRepository moderators, BanRepository bans,
                             AutomodRuleRepository automodRules, FlairRepository flairs,
                             CommunityJoinRequestRepository joinRequests,
                             CommunityApprovedSubmitterRepository approvedSubmitters, UuidV7Generator ids,
                             ObjectMapper json, ModerationAuditWriter auditWriter, AuthService authService) {
        this.communities = communities;
        this.memberships = memberships;
        this.moderators = moderators;
        this.bans = bans;
        this.automodRules = automodRules;
        this.flairs = flairs;
        this.joinRequests = joinRequests;
        this.approvedSubmitters = approvedSubmitters;
        this.ids = ids;
        this.json = json;
        this.auditWriter = auditWriter;
        this.authService = authService;
    }

    @Transactional
    public Community create(UUID creatorId, String name, String description, String type) {
        // "all" is reserved as PostController's sitewide pseudo-community (/r/all/hot etc., see F2's
        // plan) — a real community with this name would be indistinguishable from it.
        if ("all".equalsIgnoreCase(name)) {
            throw new ConflictException("community name is reserved");
        }
        if (communities.existsByName(name)) {
            throw new ConflictException("community name in use");
        }
        Community c = new Community();
        c.setId(ids.nextId());
        c.setName(name);
        c.setDescription(description);
        c.setType(type == null ? "public" : type);
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
        Community c = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        if ("private".equals(c.getType())) {
            throw new ForbiddenException("this community is private — request access instead");
        }
        addMemberDirectly(userId, communityId);
    }

    // Shared by join() (public/restricted self-serve) and approveJoinRequest() (private's approval path,
    // which deliberately bypasses join()'s own private-community rejection since approval IS the path in).
    private void addMemberDirectly(UUID userId, UUID communityId) {
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

    // isActive() gates every permission check here so a deleted/banned account's still-valid access token
    // (up to its remaining TTL — the same accepted window banAccount already relies on) can't keep
    // exercising moderator authority: deleteAccount()/banAccount() anonymize or lock the users row but
    // never touch community_moderators, and this was previously the only place that gap was reachable
    // from, since login()/refresh() were the only status checks anywhere in the app.
    public boolean hasModPermission(UUID userId, UUID communityId, int requiredBit) {
        if (!authService.isActive(userId)) {
            return false;
        }
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
        if (!authService.isActive(userId) || !moderators.existsByCommunityIdAndUserId(communityId, userId)) {
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

    // ==================== Flair ====================

    public List<Flair> listFlairs(UUID communityId, String type) {
        return type == null ? flairs.findByCommunityId(communityId) : flairs.findByCommunityIdAndType(communityId, type);
    }

    @Transactional
    public Flair addFlair(UUID actorId, UUID communityId, String text, String color, String type) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_FLAIRS);
        Flair f = new Flair();
        f.setId(ids.nextId());
        f.setCommunityId(communityId);
        f.setText(text);
        f.setColor(color);
        f.setType(type);
        return flairs.save(f);
    }

    @Transactional
    public void removeFlair(UUID actorId, UUID communityId, UUID flairId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_FLAIRS);
        Flair f = flairs.findById(flairId).orElseThrow(() -> new NotFoundException("no such flair"));
        if (!f.getCommunityId().equals(communityId)) {
            throw new NotFoundException("no such flair");
        }
        flairs.deleteById(flairId);
    }

    // Single validation chokepoint every flair-assignment path calls through — mirrors
    // MediaService.requireOwnedAndUsable's role for media. 404s rather than leaking cross-community
    // existence, same pattern as removeAutomodRule.
    public Flair requireFlairUsable(UUID communityId, UUID flairId, String expectedType) {
        Flair f = flairs.findById(flairId).orElseThrow(() -> new NotFoundException("no such flair"));
        if (!f.getCommunityId().equals(communityId)) {
            throw new NotFoundException("no such flair");
        }
        if (!f.getType().equals(expectedType)) {
            throw new BadRequestException("flair type mismatch: expected " + expectedType);
        }
        return f;
    }

    // Batched, never N+1 — same shape as MediaService.getMediaViews, used by PostService's listing methods.
    public Map<UUID, Flair> getFlairs(Set<UUID> flairIds) {
        Map<UUID, Flair> byId = new HashMap<>();
        flairs.findAllById(flairIds).forEach(f -> byId.put(f.getId(), f));
        return byId;
    }

    // Same batched shape as getFlairs above — read by PostService.attachCommunityName so a sitewide
    // "r/all" page (mixing posts from many communities) can still show which community each post is from.
    public Map<UUID, String> findNamesByIds(Set<UUID> communityIds) {
        Map<UUID, String> byId = new HashMap<>();
        communities.findAllById(communityIds).forEach(c -> byId.put(c.getId(), c.getName()));
        return byId;
    }

    @Transactional
    public void setOwnFlair(UUID userId, UUID communityId, UUID flairId) {
        Membership m = memberships.findById(new MembershipId(userId, communityId))
                .orElseThrow(() -> new NotFoundException("not a member of this community"));
        if (flairId != null) {
            requireFlairUsable(communityId, flairId, "user");
        }
        m.setFlairId(flairId);
        memberships.save(m);
    }

    @Transactional
    public void setUserFlair(UUID actorId, UUID communityId, UUID targetUserId, UUID flairId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_FLAIRS);
        Membership m = memberships.findById(new MembershipId(targetUserId, communityId))
                .orElseThrow(() -> new NotFoundException("target is not a member of this community"));
        if (flairId != null) {
            requireFlairUsable(communityId, flairId, "user");
        }
        m.setFlairId(flairId);
        memberships.save(m);
    }

    // ==================== Rules ====================

    public List<CommunityRule> getRules(UUID communityId) {
        Community c = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        return List.of(json.readValue(c.getRules(), CommunityRule[].class));
    }

    // Whole-list replace, not individual add/remove/reorder — a rules list is edited as one small ordered
    // unit in practice (max 15 entries), so index-addressed CRUD here would be meaningfully more code for
    // no real benefit.
    @Transactional
    public void setRules(UUID actorId, UUID communityId, List<CommunityRule> rules) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_RULES);
        Community c = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        c.setRules(json.writeValueAsString(rules));
        communities.save(c);
    }

    // ==================== Access control (restricted/private) ====================

    // Only the literal owner by default — requirePermission's (permissions & bit) == bit check only ever
    // matches OWNER_PERMISSIONS (every bit set) for whoever holds every single bit, which in practice
    // means the creator, not a regular moderator granted a subset of permissions. No new permission-
    // checking logic needed; this just calls the existing helper with that specific bit.
    @Transactional
    public void setType(UUID actorId, UUID communityId, String type) {
        requirePermission(actorId, communityId, CommunityModerator.OWNER_PERMISSIONS);
        Community c = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        c.setType(type);
        communities.save(c);
        // Deliberately does NOT touch existing Membership rows — a community flipping to private keeps
        // its current subscribers viewing it with no new request needed; only new members need approval.
    }

    // No-ops for public/restricted. For private, passes for a member or any kind of moderator; a null
    // viewerId (unauthenticated) always fails here, which is exactly the behavior every call site needs.
    public void requireViewAccess(UUID viewerId, UUID communityId) {
        Community c = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        if (!"private".equals(c.getType())) {
            return;
        }
        if (viewerId != null && (memberships.existsByUserIdAndCommunityId(viewerId, communityId)
                || moderators.existsByCommunityIdAndUserId(communityId, viewerId))) {
            return;
        }
        throw new ForbiddenException("this community is private");
    }

    // No-op for public. Restricted requires a moderator or an approved submitter. Private requires a
    // moderator or membership — the same check requireViewAccess does, since an approved private member
    // already has posting rights with no separate "approved submitter" concept layered on top.
    public void requirePostAccess(UUID authorId, UUID communityId) {
        Community c = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        if (moderators.existsByCommunityIdAndUserId(communityId, authorId)) {
            return;
        }
        switch (c.getType()) {
            case "restricted" -> {
                if (!approvedSubmitters.existsByCommunityIdAndUserId(communityId, authorId)) {
                    throw new ForbiddenException("only approved submitters can post in this community");
                }
            }
            case "private" -> {
                if (!memberships.existsByUserIdAndCommunityId(authorId, communityId)) {
                    throw new ForbiddenException("you must be an approved member of this private community to post");
                }
            }
            default -> { } // public: no restriction
        }
    }

    // 400s if the community isn't private — request-to-join only makes sense there; public/restricted use
    // the ordinary subscribe endpoint. Always resets cleanly to "pending" regardless of any prior state
    // (denied, or a stale approved row left over from having left and come back) rather than special-
    // casing each one — simpler and avoids edge-case bugs from stale state.
    @Transactional
    public void requestToJoin(UUID userId, UUID communityId) {
        Community c = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        if (!"private".equals(c.getType())) {
            throw new BadRequestException("this community is not private — use the normal subscribe endpoint");
        }
        if (memberships.existsByUserIdAndCommunityId(userId, communityId)) {
            return; // already a member, idempotent no-op
        }
        joinRequests.save(new CommunityJoinRequest(communityId, userId, "pending"));
    }

    public List<CommunityJoinRequest> listJoinRequests(UUID actorId, UUID communityId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_ACCESS);
        return joinRequests.findByCommunityIdAndStatus(communityId, "pending");
    }

    @Transactional
    public void approveJoinRequest(UUID actorId, UUID communityId, UUID targetUserId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_ACCESS);
        CommunityJoinRequest r = joinRequests.findById(new CommunityJoinRequestId(communityId, targetUserId))
                .orElseThrow(() -> new NotFoundException("no such join request"));
        r.setStatus("approved");
        r.setDecidedBy(actorId);
        r.setDecidedAt(Instant.now());
        joinRequests.save(r);
        addMemberDirectly(targetUserId, communityId);
    }

    @Transactional
    public void denyJoinRequest(UUID actorId, UUID communityId, UUID targetUserId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_ACCESS);
        CommunityJoinRequest r = joinRequests.findById(new CommunityJoinRequestId(communityId, targetUserId))
                .orElseThrow(() -> new NotFoundException("no such join request"));
        r.setStatus("denied");
        r.setDecidedBy(actorId);
        r.setDecidedAt(Instant.now());
        joinRequests.save(r);
    }

    @Transactional
    public void addApprovedSubmitter(UUID actorId, UUID communityId, UUID targetUserId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_ACCESS);
        if (!approvedSubmitters.existsByCommunityIdAndUserId(communityId, targetUserId)) {
            approvedSubmitters.save(new CommunityApprovedSubmitter(communityId, targetUserId, actorId));
        }
    }

    @Transactional
    public void removeApprovedSubmitter(UUID actorId, UUID communityId, UUID targetUserId) {
        requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_ACCESS);
        approvedSubmitters.deleteByCommunityIdAndUserId(communityId, targetUserId);
    }

    // ==================== Discovery ====================

    public List<Community> browseNew(Instant cursorCreatedAt, UUID cursorId, int limit) {
        return communities.findNewPage(cursorCreatedAt, cursorId, Pageable.ofSize(limit));
    }

    // Takes a double and casts internally, same convention PostService.findTopPage already uses for its
    // own rank cursor — RankCursor.FIRST_PAGE's Double.MAX_VALUE saturates to Integer.MAX_VALUE on the
    // cast, still comfortably larger than any real subscriber_count, so the first page still matches
    // every row correctly.
    public List<Community> browsePopular(double cursorSubscriberCount, UUID cursorId, int limit) {
        return communities.findPopularPage((int) cursorSubscriberCount, cursorId, Pageable.ofSize(limit));
    }

    public List<Community> searchByName(String query) {
        return communities.searchByName(query);
    }
}
