package com.redditclone.post;

import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.Thing;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Separate from PostController: that class is @RequestMapping("/r/{communityName}"), so a
// /user/{username}/... route can't live there — every method mapping would inherit that prefix.
@RestController
public class UserPostController {

    private static final String POST_KIND = "t3";
    private static final int PAGE_SIZE = 25;

    private final PostService postService;

    public UserPostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping("/user/{username}/submitted")
    public Listing<Post> submitted(@AuthenticationPrincipal UUID viewerId, @PathVariable String username,
                                    @RequestParam(required = false) String after) {
        Cursor cursor = CursorCodec.decode(after);
        List<Post> page = postService.findSubmittedByUsername(username, cursor.createdAt(), cursor.id(),
                viewerId, PAGE_SIZE);

        List<Thing<Post>> children = page.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        String next = page.isEmpty() ? null
                : CursorCodec.encode(page.getLast().getCreatedAt(), page.getLast().getId());
        return Listing.of(children, next);
    }
}
