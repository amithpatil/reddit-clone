package com.redditclone.comment;

import com.redditclone.comment.dto.CommentView;
import com.redditclone.comment.dto.PostWithCommentsView;
import com.redditclone.comment.dto.ReplyRequest;
import com.redditclone.post.PostService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class CommentController {

    private final CommentService commentService;
    private final PostService postService;

    public CommentController(CommentService commentService, PostService postService) {
        this.commentService = commentService;
        this.postService = postService;
    }

    // API design lists comment creation as POST /api/comment (postId/parentId in the body); the class
    // reference in the source plan instead nests it under /r/{communityName}/comments/{postId} — the two
    // are inconsistent, so this follows the API design table since it's the documented public contract.
    @PostMapping("/api/comment")
    public Comment reply(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ReplyRequest req) {
        return commentService.reply(userId, req.postId(), req.parentId(), req.body());
    }

    @GetMapping("/r/{communityName}/comments/{postId}")
    public PostWithCommentsView getPostWithComments(@PathVariable String communityName, @PathVariable UUID postId) {
        var post = postService.findById(postId);
        var comments = commentService.findTopLevel(postId).stream().map(CommentView::from).toList();
        return new PostWithCommentsView(post, comments);
    }
}
