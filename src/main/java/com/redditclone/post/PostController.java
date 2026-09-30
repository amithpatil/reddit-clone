package com.redditclone.post;

import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.RankCursor;
import com.redditclone.common.paging.RankCursorCodec;
import com.redditclone.common.paging.Thing;
import com.redditclone.community.CommunityService;
import com.redditclone.post.dto.CreatePostRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

@RestController
@RequestMapping("/r/{communityName}")
public class PostController {

    private static final String POST_KIND = "t3";
    private static final int PAGE_SIZE = 25;

    private final PostService postService;
    private final CommunityService communityService;

    public PostController(PostService postService, CommunityService communityService) {
        this.postService = postService;
        this.communityService = communityService;
    }

    @PostMapping("/submit")
    public Post submit(@AuthenticationPrincipal UUID userId, @PathVariable String communityName,
                        @Valid @RequestBody CreatePostRequest req,
                        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID communityId = communityService.findByName(communityName).getId();
        return postService.create(userId, communityId, req, idempotencyKey);
    }

    @GetMapping("/new")
    public Listing<Post> listNew(@PathVariable String communityName,
                                  @RequestParam(required = false) String after) {
        UUID communityId = communityService.findByName(communityName).getId();
        Cursor cursor = CursorCodec.decode(after);
        List<Post> page = postService.findNewPage(communityId, cursor.createdAt(), cursor.id(), PAGE_SIZE);

        List<Thing<Post>> children = page.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        String next = page.isEmpty() ? null
                : CursorCodec.encode(page.getLast().getCreatedAt(), page.getLast().getId());
        return Listing.of(children, next);
    }

    @GetMapping("/hot")
    public Listing<Post> listHot(@PathVariable String communityName,
                                  @RequestParam(required = false) String after) {
        UUID communityId = communityService.findByName(communityName).getId();
        RankCursor cursor = RankCursorCodec.decode(after);
        List<Post> page = postService.findHotPage(communityId, cursor.rank(), cursor.id(), PAGE_SIZE);
        return rankListing(page, Post::getHotRank);
    }

    // t = hour|day|week|month|year|all (default all), matching Reddit's own /top query param.
    @GetMapping("/top")
    public Listing<Post> listTop(@PathVariable String communityName,
                                  @RequestParam(required = false) String after,
                                  @RequestParam(name = "t", required = false, defaultValue = "all") String period) {
        UUID communityId = communityService.findByName(communityName).getId();
        RankCursor cursor = RankCursorCodec.decode(after);
        Instant since = periodCutoff(period);
        List<Post> page = postService.findTopPage(communityId, since, cursor.rank(), cursor.id(), PAGE_SIZE);
        return rankListing(page, p -> (double) p.getScore());
    }

    @GetMapping("/rising")
    public Listing<Post> listRising(@PathVariable String communityName,
                                     @RequestParam(required = false) String after) {
        UUID communityId = communityService.findByName(communityName).getId();
        RankCursor cursor = RankCursorCodec.decode(after);
        List<Post> page = postService.findRisingPage(communityId, cursor.rank(), cursor.id(), PAGE_SIZE);
        return rankListing(page, Post::getRisingRank);
    }

    @GetMapping("/controversial")
    public Listing<Post> listControversial(@PathVariable String communityName,
                                            @RequestParam(required = false) String after) {
        UUID communityId = communityService.findByName(communityName).getId();
        RankCursor cursor = RankCursorCodec.decode(after);
        List<Post> page = postService.findControversialPage(communityId, cursor.rank(), cursor.id(), PAGE_SIZE);
        return rankListing(page, Post::getControversialRank);
    }

    private Listing<Post> rankListing(List<Post> page, ToDoubleFunction<Post> rankOf) {
        List<Thing<Post>> children = page.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        String next = page.isEmpty() ? null
                : RankCursorCodec.encode(rankOf.applyAsDouble(page.getLast()), page.getLast().getId());
        return Listing.of(children, next);
    }

    private Instant periodCutoff(String period) {
        Duration window = switch (period) {
            case "hour" -> Duration.ofHours(1);
            case "day" -> Duration.ofDays(1);
            case "week" -> Duration.ofDays(7);
            case "month" -> Duration.ofDays(30);
            case "year" -> Duration.ofDays(365);
            case "all" -> null;
            default -> throw new BadRequestException("invalid period");
        };
        return window == null ? Instant.EPOCH : Instant.now().minus(window);
    }
}
