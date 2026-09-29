package com.redditclone.post;

import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
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

import java.util.List;
import java.util.UUID;

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
}
