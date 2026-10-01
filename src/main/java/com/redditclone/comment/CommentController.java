package com.redditclone.comment;

import com.redditclone.comment.dto.CommentView;
import com.redditclone.comment.dto.PostWithCommentsView;
import com.redditclone.comment.dto.ReplyRequest;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.community.CommunityService;
import com.redditclone.post.PostService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class CommentController {

    private final CommentService commentService;
    private final PostService postService;
    private final CommunityService communityService;

    public CommentController(CommentService commentService, PostService postService, CommunityService communityService) {
        this.commentService = commentService;
        this.postService = postService;
        this.communityService = communityService;
    }

    // API design lists comment creation as POST /api/comment (postId/parentId in the body); the class
    // reference in the source plan instead nests it under /r/{communityName}/comments/{postId} — the two
    // are inconsistent, so this follows the API design table since it's the documented public contract.
    @PostMapping("/api/comment")
    public Comment reply(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ReplyRequest req) {
        return commentService.reply(userId, req.postId(), req.parentId(), req.body());
    }

    @GetMapping("/r/{communityName}/comments/{postId}")
    public PostWithCommentsView getPostWithComments(@AuthenticationPrincipal UUID viewerId,
                                                      @PathVariable String communityName, @PathVariable UUID postId,
                                                      @RequestParam(required = false, defaultValue = "best") String sort) {
        UUID communityId = communityService.findByName(communityName).getId();
        communityService.requireViewAccess(viewerId, communityId);
        var post = postService.findByIdWithMedia(postId);
        // The URL's communityName must actually own this post, and a removed post is hidden here the
        // same way it's hidden from /new — otherwise the community segment is decorative and "removed"
        // content stays readable by ID.
        if (post.isRemoved() || !post.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found");
        }
        var comments = commentService.findTopLevel(postId, viewerId, sort).stream().map(CommentView::from).toList();
        return new PostWithCommentsView(post, comments);
    }
}
