package com.redditclone.vote;

import com.redditclone.common.exception.BadRequestException;
import com.redditclone.vote.dto.VoteRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/vote")
public class VoteController {

    private final VoteService voteService;

    public VoteController(VoteService voteService) {
        this.voteService = voteService;
    }

    @PostMapping
    public void vote(@AuthenticationPrincipal UUID userId, @Valid @RequestBody VoteRequest req) {
        if ("post".equals(req.targetType())) {
            voteService.castPostVote(userId, req.targetId(), req.dir());
        } else {
            voteService.castCommentVote(userId, req.targetId(), req.dir());
        }
    }

    @DeleteMapping
    public void removeVote(@AuthenticationPrincipal UUID userId, @RequestParam String targetType,
                            @RequestParam UUID targetId) {
        // Deletes the vote row — there is no neutral/0 direction stored anywhere (see Data model —
        // Resolved design decisions).
        if ("post".equals(targetType)) {
            voteService.removePostVote(userId, targetId);
        } else if ("comment".equals(targetType)) {
            voteService.removeCommentVote(userId, targetId);
        } else {
            throw new BadRequestException("targetType must be post or comment");
        }
    }
}
