package com.redditclone.comment;

import com.redditclone.auth.AuthService;
import com.redditclone.comment.dto.CommentView;
import com.redditclone.comment.dto.UserCommentView;
import com.redditclone.common.KarmaEvent;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.RankFormulas;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.VoteDelta;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
import com.redditclone.community.CommunityService;
import com.redditclone.post.Post;
import com.redditclone.post.PostService;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CommentService {

    private static final int MAX_DEPTH = 10;
    private static final int TOP_LEVEL_PAGE_SIZE = 50;
    // u/{username} mention detection. Registration itself allows any characters in a username (no
    // @Pattern on RegisterRequest), but restricting what's *mentionable* to the conventional
    // [A-Za-z0-9_] set (matching how comment ltree labels already treat "safe" identifier characters
    // elsewhere in this codebase) is a reasonable, explicit first-pass limitation, not a bug.
    // The (?<![\w/]) lookbehind requires whitespace/punctuation/start-of-string immediately before "u/" —
    // without it, "u/name" matches as a substring of ordinary text/URLs (e.g. "menu/foobar", or the "u/"
    // inside "example.com/u/alice"), firing a false "mention" notification whenever that substring happens
    // to match a real username.
    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<![\\w/])u/([A-Za-z0-9_]{3,32})");

    private final CommentRepository comments;
    private final PostService postService;
    private final UuidV7Generator ids;
    private final Sanitizer sanitizer;
    private final NamedParameterJdbcTemplate jdbc;
    private final CommunityService communityService;
    private final AuthService authService;
    private final OutboxWriter outbox;

    public CommentService(CommentRepository comments, PostService postService, UuidV7Generator ids,
                           Sanitizer sanitizer, NamedParameterJdbcTemplate jdbc,
                           CommunityService communityService, AuthService authService, OutboxWriter outbox) {
        this.comments = comments;
        this.postService = postService;
        this.ids = ids;
        this.sanitizer = sanitizer;
        this.jdbc = jdbc;
        this.communityService = communityService;
        this.authService = authService;
        this.outbox = outbox;
    }

    @Transactional
    public Comment reply(UUID authorId, UUID postId, UUID parentId, String body) {
        // 404s on a nonexistent/deleted post instead of creating an orphan; also gives us communityId
        // without a second lookup, for the ban/automod checks below.
        Post post = postService.findById(postId);
        // Private gates commenting too (if you can't view it, you can't reply to it) — but restricted does
        // not, it only gates posting, so this is intentionally requireViewAccess, not requirePostAccess.
        communityService.requireViewAccess(authorId, post.getCommunityId());
        communityService.requireNotBanned(authorId, post.getCommunityId());
        if (post.isLocked()) {
            throw new ForbiddenException("this post is locked");
        }
        String sanitizedBody = sanitizer.sanitize(body);
        Comment c = new Comment();
        c.setId(ids.nextId());
        c.setPostId(postId);
        c.setParentId(parentId);
        c.setAuthorId(authorId);
        c.setBody(sanitizedBody);

        Comment parent = null;
        if (parentId == null) {
            c.setDepth((short) 0);
            c.setPath(toLabel(c.getId()));
        } else {
            parent = comments.findById(parentId)
                    .orElseThrow(() -> new NotFoundException("parent comment not found"));
            if (!parent.getPostId().equals(postId)) {
                throw new BadRequestException("parent comment does not belong to this post");
            }
            if (parent.getDepth() >= MAX_DEPTH) {
                throw new BadRequestException("max comment depth reached");
            }
            c.setDepth((short) (parent.getDepth() + 1));
            c.setPath(parent.getPath() + "." + toLabel(c.getId()));
            comments.incrementChildCount(parentId);
        }
        // Same ordering as PostService.create(): id is already assigned, evaluated before save() so a
        // "remove" verdict lands in the very first row written, and the audit/report rows automod writes
        // can reference this comment's real id from the moment it exists.
        int authorKarma = authService.getKarmaComment(authorId);
        if (communityService.evaluateAutomod(post.getCommunityId(), "comment", c.getId(), null, sanitizedBody, authorKarma)) {
            c.setRemoved(true);
        }
        Comment saved = comments.save(c);
        postService.incrementCommentCount(postId);
        if (!saved.isRemoved()) {
            notifyFanOut(saved, post, parent, authorId, sanitizedBody);
        }
        return saved;
    }

    // Reply/mention notifications, written as outbox events (common.OutboxWriter) and fanned out into
    // notifications rows by notify.NotificationOutboxWorker in the same batch-processing shape already
    // used for vote score/karma updates — comment and notify have no dependency relationship to route a
    // direct write through, so a shared common writer is the correct fix, same as ModerationAuditWriter.
    // Skipped entirely for an automod-removed comment (checked by the caller) — no point notifying about a
    // reply nobody will ever see.
    private void notifyFanOut(Comment c, Post post, Comment parent, UUID authorId, String sanitizedBody) {
        if (parent == null) {
            if (!post.getAuthorId().equals(authorId)) {
                outbox.writeEvent("notification", Map.of(
                        "userId", post.getAuthorId(),
                        "type", "post_reply",
                        "source", Map.of("postId", post.getId(), "communityId", post.getCommunityId())));
            }
        } else if (!parent.getAuthorId().equals(authorId)) {
            outbox.writeEvent("notification", Map.of(
                    "userId", parent.getAuthorId(),
                    "type", "reply",
                    "source", Map.of("commentId", parent.getId(), "postId", post.getId(), "communityId", post.getCommunityId())));
        }
        notifyMentions(c, post, authorId, sanitizedBody);
    }

    // Concrete design choice for "mention" detection (the source plan lists it as a notification type
    // but never specifies how): scan for u/{username} tokens, resolve every distinct one in a single
    // batched query (AuthService.findUserIdsByUsernames) rather than one SELECT per mention, then write
    // one event per resolved user excluding the author. An unmatched/typo'd username is silently
    // skipped — the same "a soft failure here shouldn't block the main action" principle already applied
    // to automod's malformed-rule handling in CommunityService.
    private void notifyMentions(Comment c, Post post, UUID authorId, String sanitizedBody) {
        Matcher matcher = MENTION_PATTERN.matcher(sanitizedBody);
        Set<String> usernames = new HashSet<>();
        while (matcher.find()) {
            usernames.add(matcher.group(1));
        }
        if (usernames.isEmpty()) {
            return;
        }
        List<Map<String, Object>> payloads = authService.findUserIdsByUsernames(usernames).values().stream()
                .filter(mentionedId -> !mentionedId.equals(authorId))
                .<Map<String, Object>>map(mentionedId -> Map.of(
                        "userId", mentionedId,
                        "type", "mention",
                        "source", Map.of("commentId", c.getId(), "postId", post.getId(), "communityId", post.getCommunityId())))
                .toList();
        outbox.writeEvents("notification", payloads);
    }

    // best is Reddit's own default (Wilson confidence, not raw score). Builds a genuinely nested tree
    // (CommentView.replies), not a flat top-level-only list — see F3's plan. Root comments are still
    // capped at TOP_LEVEL_PAGE_SIZE with no cursor (real pagination is feature 8's job), but every reply
    // beneath a root is fetched eagerly and sorted recursively at every depth with the same comparator
    // logic as the root query's own ORDER BY, matching how Reddit itself sorts a whole thread, not just
    // its first level.
    public List<CommentView> findCommentTree(UUID postId, UUID viewerId, String sort) {
        List<Comment> roots = findTopLevel(postId, viewerId, sort);
        List<Comment> replies = comments.findRepliesByPostId(postId, viewerId);

        List<Comment> all = new ArrayList<>(roots);
        all.addAll(replies);
        attachAuthorUsernames(all);

        Map<UUID, List<Comment>> childrenByParent = new HashMap<>();
        for (Comment c : replies) {
            childrenByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        }
        Comparator<Comment> comparator = comparatorFor(sort);
        return roots.stream().map(r -> toViewRecursive(r, childrenByParent, comparator)).toList();
    }

    private CommentView toViewRecursive(Comment c, Map<UUID, List<Comment>> childrenByParent, Comparator<Comment> comparator) {
        List<Comment> kids = childrenByParent.getOrDefault(c.getId(), List.of());
        List<CommentView> childViews = kids.stream()
                .sorted(comparator)
                .map(k -> toViewRecursive(k, childrenByParent, comparator))
                .toList();
        return CommentView.from(c, childViews);
    }

    private List<Comment> findTopLevel(UUID postId, UUID viewerId, String sort) {
        Pageable limit = Pageable.ofSize(TOP_LEVEL_PAGE_SIZE);
        return switch (sort) {
            case "best" -> comments.findTopLevelByBest(postId, viewerId, limit);
            case "top" -> comments.findTopLevelByTop(postId, viewerId, limit);
            case "new" -> comments.findTopLevelByNew(postId, viewerId, limit);
            case "old" -> comments.findTopLevelByOld(postId, viewerId, limit);
            case "controversial" -> comments.findTopLevelByControversial(postId, viewerId, limit);
            default -> throw new BadRequestException("invalid comment sort");
        };
    }

    // Mirrors each findTopLevelBy*'s own ORDER BY exactly, as a Java Comparator — used to sort each
    // reply group (a comment's direct children) at every depth of the tree above, since there's no SQL
    // query shaped to sort an arbitrary-depth tree recursively. Root comments don't need this: the
    // findTopLevelBy* query above already returns them in the right order.
    private Comparator<Comment> comparatorFor(String sort) {
        return switch (sort) {
            case "best" -> Comparator.comparingDouble(Comment::getBestRank).reversed()
                    .thenComparing(Comparator.comparing(Comment::getId).reversed());
            case "top" -> Comparator.comparingInt(Comment::getScore).reversed()
                    .thenComparing(Comparator.comparing(Comment::getId).reversed());
            case "new" -> Comparator.comparing(Comment::getCreatedAt).reversed()
                    .thenComparing(Comparator.comparing(Comment::getId).reversed());
            case "old" -> Comparator.comparing(Comment::getCreatedAt).thenComparing(Comment::getId);
            case "controversial" -> Comparator.comparingDouble(Comment::getControversialRank).reversed()
                    .thenComparing(Comparator.comparing(Comment::getId).reversed());
            default -> throw new BadRequestException("invalid comment sort");
        };
    }

    // Same batched-IN-query shape as PostService.attachAuthorUsername, via the same AuthService method —
    // called once across both roots and replies combined, not per level, so a thread with many commenters
    // still costs exactly one extra query regardless of its depth or shape.
    private void attachAuthorUsernames(List<Comment> allComments) {
        if (allComments.isEmpty()) {
            return;
        }
        Set<UUID> authorIds = new HashSet<>();
        for (Comment c : allComments) {
            authorIds.add(c.getAuthorId());
        }
        Map<UUID, String> usernames = authService.findUsernamesByIds(authorIds);
        for (Comment c : allComments) {
            c.setAuthorUsername(usernames.get(c.getAuthorId()));
        }
    }

    public Comment findById(UUID commentId) {
        return comments.findById(commentId).orElseThrow(() -> new NotFoundException("comment not found"));
    }

    // Batched, no tree/display attach — same "internal lookup, not a display path" reasoning as
    // post.PostService.findAllByIds (added in F7 for the identical purpose: ModerationService's mod queue,
    // F8, batch-resolving a page's comment-type targets to a preview in one query instead of one per row).
    public List<Comment> findAllByIds(Set<UUID> ids) {
        return comments.findAllById(ids);
    }

    // A user's "comments" profile tab (F7). Attaches postTitle/communityName in two batched queries (one
    // per page, never one per comment): postService.findAllByIds for the page's distinct post ids, then
    // communityService.findNamesByIds for those posts' distinct community ids — the same two-hop batching
    // pattern post.PostService.attachAll already uses for Post.communityName.
    public List<UserCommentView> findByAuthor(String username, Instant cursorCreatedAt, UUID cursorId,
                                               UUID viewerId, int limit) {
        UUID authorId = authService.findUserIdByUsername(username)
                .orElseThrow(() -> new NotFoundException("no such user"));
        List<Comment> page = comments.findByAuthorId(authorId, cursorCreatedAt, cursorId, viewerId, Pageable.ofSize(limit));
        if (page.isEmpty()) {
            return List.of();
        }

        Set<UUID> postIds = page.stream().map(Comment::getPostId).collect(Collectors.toSet());
        Map<UUID, Post> postsById = postService.findAllByIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));

        Set<UUID> communityIds = postsById.values().stream().map(Post::getCommunityId)
                .collect(Collectors.toSet());
        Map<UUID, String> communityNames = communityService.findNamesByIds(communityIds);

        return page.stream().map(c -> {
            Post post = postsById.get(c.getPostId());
            String postTitle = post != null ? post.getTitle() : null;
            String communityName = post != null ? communityNames.get(post.getCommunityId()) : null;
            return new UserCommentView(c.getId(), c.getPostId(), postTitle, communityName, c.getParentId(),
                    c.getBody(), c.getScore(), c.getCreatedAt());
        }).toList();
    }

    // For ModerationService's human-initiated removal path — Comment already has a public `removed`
    // setter (unlike score/best_rank/etc, it was never migrated to the raw-SQL-bulk-only pattern).
    @Transactional
    public void markRemoved(UUID commentId) {
        Comment c = findById(commentId);
        c.setRemoved(true);
        comments.save(c);
    }

    // Applies a batch of grouped vote deltas (one entry per comment touched, not per vote — see
    // OutboxWorker) via raw SQL bulk updates rather than loading Comment entities: avoids the same
    // stale-persistence-context class of bug incrementChildCount's clearAutomatically works around, and
    // matches the plan's "one grouped UPDATE per target, not one per vote" design goal. Returns one
    // KarmaEvent per comment whose net score actually changed, for the caller to attribute to karma_log
    // and users.karma_comment — this module never touches those tables itself (see ModuleBoundaryTest).
    @Transactional
    public List<KarmaEvent> applyVoteDeltas(Map<UUID, VoteDelta> deltas) {
        if (deltas.isEmpty()) {
            return List.of();
        }
        SqlParameterSource[] deltaParams = deltas.entrySet().stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("id", e.getKey())
                        .addValue("score", e.getValue().scoreDelta())
                        .addValue("ups", e.getValue().upsDelta())
                        .addValue("downs", e.getValue().downsDelta()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                UPDATE comments SET score = score + :score, ups = ups + :ups, downs = downs + :downs
                WHERE id = :id
                """, deltaParams);

        Set<UUID> ids = deltas.keySet();
        List<Map<String, Object>> fresh = jdbc.queryForList(
                "SELECT id, author_id, ups, downs FROM comments WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids));

        List<SqlParameterSource> rankParams = new ArrayList<>();
        List<KarmaEvent> karmaEvents = new ArrayList<>();
        for (Map<String, Object> row : fresh) {
            UUID id = (UUID) row.get("id");
            UUID authorId = (UUID) row.get("author_id");
            int ups = (Integer) row.get("ups");
            int downs = (Integer) row.get("downs");
            double bestRank = RankFormulas.bestRank(ups, downs);
            double controversialRank = RankFormulas.controversialRank(ups, downs);
            rankParams.add(new MapSqlParameterSource().addValue("id", id)
                    .addValue("bestRank", bestRank).addValue("controversialRank", controversialRank));

            int scoreDelta = deltas.get(id).scoreDelta();
            if (scoreDelta != 0) {
                karmaEvents.add(new KarmaEvent(authorId, scoreDelta, id));
            }
        }
        jdbc.batchUpdate("""
                UPDATE comments SET best_rank = :bestRank, controversial_rank = :controversialRank
                WHERE id = :id
                """, rankParams.toArray(new SqlParameterSource[0]));
        return karmaEvents;
    }

    // ltree labels only allow [A-Za-z0-9_] — a UUID's hyphens aren't valid, so this strips them to a
    // plain 32-char hex label.
    private String toLabel(UUID id) {
        return id.toString().replace("-", "");
    }
}
