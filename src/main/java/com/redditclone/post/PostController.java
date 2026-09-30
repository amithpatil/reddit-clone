package com.redditclone.post;

import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.RankCursor;
import com.redditclone.common.paging.RankCursorCodec;
import com.redditclone.common.paging.Thing;
import com.redditclone.community.CommunityService;
import com.redditclone.community.Flair;
import com.redditclone.post.dto.CreatePostRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

@RestController
@RequestMapping("/r/{communityName}")
public class PostController {

    private static final String POST_KIND = "t3";
    private static final int PAGE_SIZE = 25;

    private final PostService postService;
    private final CommunityService communityService;
    private final FeedCacheService feedCache;
    private final ObjectMapper json;

    public PostController(PostService postService, CommunityService communityService,
                           FeedCacheService feedCache, ObjectMapper json) {
        this.postService = postService;
        this.communityService = communityService;
        this.feedCache = feedCache;
        this.json = json;
    }

    @PostMapping("/submit")
    public Post submit(@AuthenticationPrincipal UUID userId, @PathVariable String communityName,
                        @Valid @RequestBody CreatePostRequest req,
                        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID communityId = communityService.findByName(communityName).getId();
        return postService.create(userId, communityId, req, idempotencyKey);
    }

    @GetMapping("/new")
    public Listing<Post> listNew(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                  @RequestParam(required = false) String after) {
        UUID communityId = communityService.findByName(communityName).getId();
        Cursor cursor = CursorCodec.decode(after);
        List<Post> page = postService.findNewPage(communityId, cursor.createdAt(), cursor.id(), viewerId, PAGE_SIZE);

        List<Thing<Post>> children = page.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        String next = page.isEmpty() ? null
                : CursorCodec.encode(page.getLast().getCreatedAt(), page.getLast().getId());
        return Listing.of(children, next);
    }

    // Page 1 only (no `after`) is cache-eligible — see FeedCacheService. Uniformly returns a raw JSON
    // string (via ResponseEntity) for both the cache-hit and freshly-computed paths, rather than
    // deserializing a cached body back into a Listing<Post> only to reserialize it identically.
    // The cache is per-community, not per-viewer, so it's fundamentally incompatible with a per-viewer
    // hidden-items filter — only used at all when viewerId == null (unauthenticated); an authenticated
    // request (which may have a hide-list) always computes fresh.
    @GetMapping("/hot")
    public ResponseEntity<String> listHot(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                           @RequestParam(required = false) String after) {
        boolean firstPage = after == null || after.isBlank();
        if (firstPage && viewerId == null) {
            Optional<String> cached = feedCache.getHotPage(communityName);
            if (cached.isPresent()) {
                return jsonResponse(cached.get());
            }
        }
        UUID communityId = communityService.findByName(communityName).getId();
        RankCursor cursor = RankCursorCodec.decode(after, "hot");
        List<Post> page = postService.findHotPage(communityId, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        Listing<Post> listing = rankListing("hot", page, Post::getHotRank, null);
        String body = json.writeValueAsString(listing);
        if (firstPage && viewerId == null) {
            feedCache.putHotPage(communityName, body);
        }
        return jsonResponse(body);
    }

    private ResponseEntity<String> jsonResponse(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    // Public — a community's flair list is needed to render the submit-post flair picker, or a self-assign
    // user-flair picker, before the caller has necessarily even logged in. type is optional; when present
    // it must be "post" or "user", matching the flairs.type CHECK constraint.
    @GetMapping("/flairs")
    public List<Flair> flairs(@PathVariable String communityName, @RequestParam(required = false) String type) {
        if (type != null && !type.equals("user") && !type.equals("post")) {
            throw new BadRequestException("invalid flair type");
        }
        UUID communityId = communityService.findByName(communityName).getId();
        return communityService.listFlairs(communityId, type);
    }

    // No pagination — a relevance ranking (ts_rank) isn't a stable keyset sort key, see PostService.search.
    @GetMapping("/search")
    public Listing<Post> search(@PathVariable String communityName, @RequestParam("q") String query) {
        UUID communityId = communityService.findByName(communityName).getId();
        List<Post> results = postService.search(communityId, query);
        List<Thing<Post>> children = results.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        return Listing.of(children, null);
    }

    // t = hour|day|week|month|year|all (default all), matching Reddit's own /top query param. The cutoff
    // is anchored to the instant page 1 was requested and carried forward in the cursor (see RankCursor's
    // anchorEpochSecond) rather than recomputed from Instant.now() on every page — otherwise a client
    // paging over several minutes gets a moving window that can skip or duplicate rows at the boundary.
    @GetMapping("/top")
    public Listing<Post> listTop(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                  @RequestParam(required = false) String after,
                                  @RequestParam(name = "t", required = false, defaultValue = "all") String period) {
        UUID communityId = communityService.findByName(communityName).getId();
        RankCursor cursor = RankCursorCodec.decode(after, "top");
        Instant anchor = cursor.anchorEpochSecond() != null
                ? Instant.ofEpochSecond(cursor.anchorEpochSecond())
                : Instant.now();
        Instant since = periodCutoff(period, anchor);
        List<Post> page = postService.findTopPage(communityId, since, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        return rankListing("top", page, p -> (double) p.getScore(), anchor.getEpochSecond());
    }

    @GetMapping("/rising")
    public Listing<Post> listRising(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                     @RequestParam(required = false) String after) {
        UUID communityId = communityService.findByName(communityName).getId();
        RankCursor cursor = RankCursorCodec.decode(after, "rising");
        List<Post> page = postService.findRisingPage(communityId, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        return rankListing("rising", page, Post::getRisingRank, null);
    }

    @GetMapping("/controversial")
    public Listing<Post> listControversial(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                            @RequestParam(required = false) String after) {
        UUID communityId = communityService.findByName(communityName).getId();
        RankCursor cursor = RankCursorCodec.decode(after, "controversial");
        List<Post> page = postService.findControversialPage(communityId, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        return rankListing("controversial", page, Post::getControversialRank, null);
    }

    private Listing<Post> rankListing(String sort, List<Post> page, ToDoubleFunction<Post> rankOf, Long anchorEpochSecond) {
        List<Thing<Post>> children = page.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        String next = page.isEmpty() ? null
                : RankCursorCodec.encode(sort, rankOf.applyAsDouble(page.getLast()), page.getLast().getId(), anchorEpochSecond);
        return Listing.of(children, next);
    }

    private Instant periodCutoff(String period, Instant anchor) {
        Duration window = switch (period) {
            case "hour" -> Duration.ofHours(1);
            case "day" -> Duration.ofDays(1);
            case "week" -> Duration.ofDays(7);
            case "month" -> Duration.ofDays(30);
            case "year" -> Duration.ofDays(365);
            case "all" -> null;
            default -> throw new BadRequestException("invalid period");
        };
        return window == null ? Instant.EPOCH : anchor.minus(window);
    }
}
