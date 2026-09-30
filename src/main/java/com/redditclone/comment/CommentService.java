package com.redditclone.comment;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
import com.redditclone.post.PostService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CommentService {

    private static final int MAX_DEPTH = 10;
    private static final int TOP_LEVEL_PAGE_SIZE = 50;

    private final CommentRepository comments;
    private final PostService postService;
    private final UuidV7Generator ids;
    private final Sanitizer sanitizer;

    public CommentService(CommentRepository comments, PostService postService, UuidV7Generator ids, Sanitizer sanitizer) {
        this.comments = comments;
        this.postService = postService;
        this.ids = ids;
        this.sanitizer = sanitizer;
    }

    @Transactional
    public Comment reply(UUID authorId, UUID postId, UUID parentId, String body) {
        postService.findById(postId); // 404s on a nonexistent/deleted post instead of creating an orphan
        Comment c = new Comment();
        c.setId(ids.nextId());
        c.setPostId(postId);
        c.setParentId(parentId);
        c.setAuthorId(authorId);
        c.setBody(sanitizer.sanitize(body));

        if (parentId == null) {
            c.setDepth((short) 0);
            c.setPath(toLabel(c.getId()));
        } else {
            Comment parent = comments.findById(parentId)
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
        Comment saved = comments.save(c);
        postService.incrementCommentCount(postId);
        return saved;
    }

    public List<Comment> findTopLevel(UUID postId) {
        return comments.findTopLevel(postId, Pageable.ofSize(TOP_LEVEL_PAGE_SIZE));
    }

    // ltree labels only allow [A-Za-z0-9_] — a UUID's hyphens aren't valid, so this strips them to a
    // plain 32-char hex label.
    private String toLabel(UUID id) {
        return id.toString().replace("-", "");
    }
}
